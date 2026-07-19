package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.SimulationClockJumpAction;
import stock.back.service.market.vo.SimulationClockResponse;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationClockServiceTest {

    @Test
    void currentSnapshot_staleRunningClock_usesHeartbeatAsRealTimeBoundary() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values (?, ?, 7200, 7200, true, ?, ?, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0)
        );
        SimulationClockService service = service(jdbcTemplate);

        SimulationClockSnapshot snapshot = service.currentSnapshot();

        assertThat(snapshot.stale()).isTrue();
        assertThat(snapshot.simulationDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(snapshot.simulationDayStart()).isEqualTo(LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(snapshot.realDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 1, 11, 0));
        assertThat(snapshot.realDayStart()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
        assertThat(service.currentDayStart()).isEqualTo(LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(service.currentRealDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 12, 0));
    }

    @Test
    void jumpToSafePreset_regularSession_movesToTodayMarketClose() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 3_000L);
        SimulationClockService service = service(jdbcTemplate);

        SimulationClockResponse response = service.jumpToSafePreset(SimulationClockJumpAction.TODAY_MARKET_CLOSE);

        assertThat(response.simulationDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 1, 18, 0));
        assertThat(response.marketSession()).isEqualTo(SimulationMarketSession.AFTER_CLOSE);
        assertThat(response.marketOpenTime()).isEqualTo(LocalTime.of(6, 0));
        assertThat(response.marketCloseTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(response.accumulatedRealSeconds()).isEqualTo(5_400L);
    }

    @Test
    void jumpToSafePreset_regularSession_rejectsNextMarketOpen() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 3_000L);
        SimulationClockService service = service(jdbcTemplate);

        assertThatThrownBy(() -> service.jumpToSafePreset(SimulationClockJumpAction.NEXT_MARKET_OPEN))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("regular session");
    }

    @Test
    void jumpToSafePreset_regularSessionWithStaleActiveBusinessDate_rejectsMarketCloseBoundary() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 10_200L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertMarketBusinessState(
                jdbcTemplate,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 1, 2)
        );
        SimulationClockService service = service(jdbcTemplate);

        assertThatThrownBy(() -> service.jumpToSafePreset(SimulationClockJumpAction.TODAY_MARKET_CLOSE))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Active business date must match");
    }

    @Test
    void jumpToSafePreset_afterCloseWithReadyCycle_movesToNextMarketOpen() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 5_700L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertPostCloseCycle(jdbcTemplate, LocalDate.of(2026, 1, 1), "READY_TO_OPEN", 1L, 1L, 0L);
        SimulationClockService service = service(jdbcTemplate);

        SimulationClockResponse response = service.jumpToSafePreset(SimulationClockJumpAction.NEXT_MARKET_OPEN);

        assertThat(response.simulationDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 6, 0));
        assertThat(response.marketSession()).isEqualTo(SimulationMarketSession.REGULAR);
        assertThat(response.accumulatedRealSeconds()).isEqualTo(9_000L);
    }

    @Test
    void jumpToSafePreset_afterCloseWithCompletedItems_movesBeforeFormerDelay() {
        // given
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 5_500L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertPostCloseCycle(jdbcTemplate, LocalDate.of(2026, 1, 1), "READY_TO_OPEN", 0L, 0L, 0L);
        SimulationClockService service = service(jdbcTemplate);

        // when
        SimulationClockResponse response = service.jumpToSafePreset(SimulationClockJumpAction.NEXT_MARKET_OPEN);

        // then
        assertThat(response.simulationDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 6, 0));
        assertThat(response.marketSession()).isEqualTo(SimulationMarketSession.REGULAR);
    }

    @Test
    void jumpToSafePreset_afterCloseWithMissingPortfolioSettlement_rejectsNextMarketOpen() {
        // given
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 5_700L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertPostCloseCycle(jdbcTemplate, LocalDate.of(2026, 1, 1), "LEDGER_FROZEN", 1L, 0L, 1L);
        SimulationClockService service = service(jdbcTemplate);

        // when / then
        assertThatThrownBy(() -> service.jumpToSafePreset(SimulationClockJumpAction.NEXT_MARKET_OPEN))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("market-open preparation");
    }

    @Test
    void jumpToSafePreset_afterClose_movesToNextSimulationDayStart() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 5_700L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertMarketBusinessState(
                jdbcTemplate,
                LocalDate.of(2026, 1, 1),
                null,
                LocalDate.of(2026, 1, 1)
        );
        insertPostCloseCycle(jdbcTemplate, LocalDate.of(2026, 1, 1), "PORTFOLIO_SETTLED", 1L, 1L, 0L);
        SimulationClockService service = service(jdbcTemplate);

        SimulationClockResponse response = service.jumpToSafePreset(SimulationClockJumpAction.NEXT_SIMULATION_DAY_START);

        assertThat(response.simulationDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(response.marketSession()).isEqualTo(SimulationMarketSession.PRE_OPEN);
        assertThat(response.accumulatedRealSeconds()).isEqualTo(7_200L);
    }

    @Test
    void currentResponse_afterCloseWithSettledCycle_exposesOnlyNextDayBoundary() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 5_700L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertMarketBusinessState(
                jdbcTemplate,
                LocalDate.of(2026, 1, 1),
                null,
                LocalDate.of(2026, 1, 1)
        );
        insertPostCloseCycle(jdbcTemplate, LocalDate.of(2026, 1, 1), "PORTFOLIO_SETTLED", 1L, 1L, 0L);
        SimulationClockService service = service(jdbcTemplate);

        SimulationClockResponse response = service.currentResponse();

        assertThat(response)
                .extracting(
                        SimulationClockResponse::activeBusinessDate,
                        SimulationClockResponse::preparingBusinessDate,
                        SimulationClockResponse::postCloseProcessingCompleted,
                        SimulationClockResponse::marketOpenReady,
                        SimulationClockResponse::availableJumpActions
                )
                .containsExactly(
                        LocalDate.of(2026, 1, 1),
                        null,
                        true,
                        false,
                        java.util.List.of(SimulationClockJumpAction.NEXT_SIMULATION_DAY_START)
                );
    }

    @Test
    void jumpToSafePreset_afterCloseWithRawClockAheadOfActiveDate_rejectsNextDayBoundary() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 12_900L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertMarketBusinessState(
                jdbcTemplate,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 1, 2)
        );
        insertPostCloseCycle(jdbcTemplate, LocalDate.of(2026, 1, 1), "PORTFOLIO_SETTLED", 1L, 1L, 0L);
        SimulationClockService service = service(jdbcTemplate);

        assertThatThrownBy(() -> service.jumpToSafePreset(SimulationClockJumpAction.NEXT_SIMULATION_DAY_START))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("active business-date alignment");
    }

    @Test
    void jumpToSafePreset_afterCloseWithPendingPostClose_rejectsNextMarketOpen() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 5_700L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        SimulationClockService service = service(jdbcTemplate);

        assertThatThrownBy(() -> service.jumpToSafePreset(SimulationClockJumpAction.NEXT_MARKET_OPEN))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("market-open preparation");
    }

    @Test
    void jumpToSafePreset_afterCloseWithSettledButNotPrepared_rejectsNextMarketOpen() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 5_700L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertPostCloseCycle(jdbcTemplate, LocalDate.of(2026, 1, 1), "PORTFOLIO_SETTLED", 1L, 1L, 0L);
        SimulationClockService service = service(jdbcTemplate);

        assertThatThrownBy(() -> service.jumpToSafePreset(SimulationClockJumpAction.NEXT_MARKET_OPEN))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("market-open preparation");
    }

    @Test
    void jumpToSafePreset_preOpenWithPendingPreviousPostClose_rejectsNextMarketOpen() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 8_700L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        SimulationClockService service = service(jdbcTemplate);

        assertThatThrownBy(() -> service.jumpToSafePreset(SimulationClockJumpAction.NEXT_MARKET_OPEN))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("market-open preparation");
    }

    @Test
    void jumpToSafePreset_preOpenWithCompletedPreviousPostClose_movesToTodayMarketOpen() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 8_700L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertMarketBusinessState(
                jdbcTemplate,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 1, 2)
        );
        insertPostCloseCycle(jdbcTemplate, LocalDate.of(2026, 1, 1), "READY_TO_OPEN", 1L, 1L, 0L);
        SimulationClockService service = service(jdbcTemplate);

        SimulationClockResponse response = service.jumpToSafePreset(SimulationClockJumpAction.NEXT_MARKET_OPEN);

        assertThat(response.simulationDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 6, 0));
        assertThat(response.marketSession()).isEqualTo(SimulationMarketSession.REGULAR);
    }

    @Test
    void jumpToSafePreset_preOpenWithRawClockAheadOfPreparedDate_rejectsNextMarketOpen() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        insertPausedClock(jdbcTemplate, 15_900L);
        insertEnabledOrderBookInstrument(jdbcTemplate);
        insertMarketBusinessState(
                jdbcTemplate,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 1, 3)
        );
        insertPostCloseCycle(jdbcTemplate, LocalDate.of(2026, 1, 1), "READY_TO_OPEN", 1L, 1L, 0L);
        insertSkippedPostCloseCycle(jdbcTemplate, 2L, LocalDate.of(2026, 1, 2));
        SimulationClockService service = service(jdbcTemplate);

        assertThatThrownBy(() -> service.jumpToSafePreset(SimulationClockJumpAction.NEXT_MARKET_OPEN))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("prepared business-date alignment");
    }

    @Test
    void jumpToSafePreset_staleRunningClock_movesTimeButDoesNotRestartClock() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values (?, ?, 7200, 7200, true, ?, ?, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0)
        );
        SimulationClockService service = service(jdbcTemplate);

        SimulationClockResponse response = service.jumpToSafePreset(SimulationClockJumpAction.TODAY_MARKET_CLOSE);

        assertThat(response.simulationDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 18, 0));
        assertThat(response.running()).isFalse();
        assertThat(response.stale()).isFalse();
    }

    private SimulationClockService service(JdbcTemplate jdbcTemplate) {
        SimulationClockService service = new SimulationClockService(JdbcClient.create(jdbcTemplate));
        ReflectionTestUtils.setField(service, "baseDateValue", "");
        ReflectionTestUtils.setField(service, "realSecondsPerSimulationDay", 7200);
        ReflectionTestUtils.setField(service, "staleAfterSeconds", 30L);
        ReflectionTestUtils.setField(service, "openTimeValue", "06:00");
        ReflectionTestUtils.setField(service, "closeTimeValue", "18:00");
        return service;
    }

    private void insertPausedClock(JdbcTemplate jdbcTemplate, long accumulatedRealSeconds) {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values (?, ?, 7200, ?, false, null, null, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                accumulatedRealSeconds,
                now,
                now
        );
    }

    private void insertEnabledOrderBookInstrument(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(symbol, enabled)
                values ('SIM001', true)
                """
        );
    }

    private void insertMarketBusinessState(
            JdbcTemplate jdbcTemplate,
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            LocalDate rawSimulationDate
    ) {
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date,
                    raw_simulation_date, version, created_at, updated_at
                )
                values ('DEFAULT', ?, ?, ?, 0, current_timestamp, current_timestamp)
                """,
                activeBusinessDate,
                preparingBusinessDate,
                rawSimulationDate
        );
    }

    private void insertSkippedPostCloseCycle(
            JdbcTemplate jdbcTemplate,
            long cycleId,
            LocalDate businessDate
    ) {
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(
                    id, business_date, scope_type, scope_key, cycle_kind, phase, status
                )
                values (?, ?, 'FULL_MARKET', 'ALL', 'SKIPPED', 'COMPLETED', 'COMPLETED')
                """,
                cycleId,
                businessDate
        );
    }

    private void insertPostCloseCycle(
            JdbcTemplate jdbcTemplate,
            LocalDate businessDate,
            String phase,
            long targetAccountCount,
            long settledAccountCount,
            long missingAccountCount
    ) {
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(
                    id, business_date, scope_type, scope_key, cycle_kind, phase, status
                )
                values (1, ?, 'FULL_MARKET', 'ALL', 'TRADING', ?, 'PENDING')
                """,
                businessDate,
                phase
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle_metric(
                    close_cycle_id, settlement_target_account_count,
                    settled_account_count, settlement_missing_account_count
                )
                values (1, ?, ?, ?)
                """,
                targetAccountCount,
                settledAccountCount,
                missingAccountCount
        );
    }

    private JdbcTemplate createJdbcTemplate() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:simulation_clock_back_%d;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(System.nanoTime()),
                "sa",
                ""
        ));
        jdbcTemplate.execute(
                """
                create table stock_simulation_clock (
                  clock_id varchar(40) not null primary key,
                  base_simulation_date date not null,
                  real_seconds_per_simulation_day int not null,
                  accumulated_real_seconds bigint not null default 0,
                  running boolean not null default false,
                  last_started_at timestamp null,
                  last_heartbeat_at timestamp null,
                  timezone varchar(50) not null default 'Asia/Seoul',
                  created_at timestamp not null,
                  updated_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_order_book_instrument (
                  symbol varchar(20) not null primary key,
                  enabled boolean not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_market_business_state (
                  state_id varchar(40) not null primary key,
                  active_business_date date not null,
                  preparing_business_date date null,
                  raw_simulation_date date not null,
                  version bigint not null default 0,
                  created_at timestamp not null,
                  updated_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_post_close_cycle (
                  id bigint not null primary key,
                  business_date date not null,
                  scope_type varchar(20) not null,
                  scope_key varchar(40) not null,
                  cycle_kind varchar(20) not null,
                  phase varchar(60) not null,
                  status varchar(20) not null,
                  unique (business_date, scope_type, scope_key)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_post_close_cycle_metric (
                  close_cycle_id bigint not null primary key,
                  settlement_target_account_count bigint not null,
                  settled_account_count bigint not null,
                  settlement_missing_account_count bigint not null
                )
                """
        );
        return jdbcTemplate;
    }
}
