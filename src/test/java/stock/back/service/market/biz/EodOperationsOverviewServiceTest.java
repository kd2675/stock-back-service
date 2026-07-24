package stock.back.service.market.biz;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class EodOperationsOverviewServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 15);
    private static final LocalDateTime CLOSED_AT = BUSINESS_DATE.atTime(18, 0);
    private static final Pattern HIGH_VOLUME_LEDGER_READ = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+(?:stock_order|stock_execution|stock_close_account_snapshot|"
                    + "stock_holding_snapshot|portfolio_snapshot)\\b"
    );

    @Test
    void overviewQuerySource_neverReadsHighVolumeLedgerOrSnapshotHistory() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/stock/back/service/market/biz/EodOperationsOverviewService.java"),
                StandardCharsets.UTF_8
        );

        assertThat(HIGH_VOLUME_LEDGER_READ.matcher(source).find()).isFalse();
    }

    @Test
    void overview_activeCycle_readsPreaggregatedOperationalRows() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        EodOperationsOverviewService service = new EodOperationsOverviewService(JdbcClient.create(jdbcTemplate));
        seedBusinessState(jdbcTemplate);
        jdbcTemplate.update(
                "insert into stock_order_book_market_config(symbol, enabled, market_status, updated_at) values ('EOD001', true, 'CLOSED', ?)",
                CLOSED_AT
        );
        jdbcTemplate.update(
                """
                insert into stock_market_close_run(
                    symbol, business_date, closed_at, status, cancelled_order_count,
                    holding_snapshot_count, price_rollover_count, created_at, completed_at
                ) values (null, ?, ?, 'COMPLETED', 12, 40, 3, ?, ?)
                """,
                BUSINESS_DATE,
                CLOSED_AT,
                CLOSED_AT,
                CLOSED_AT.plusSeconds(3)
        );
        Long closeRunId = jdbcTemplate.queryForObject("select max(id) from stock_market_close_run", Long.class);
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(
                    business_date, scope_type, scope_key, cycle_kind, phase, status,
                    phase_revision, version, close_run_id, settlement_eligible_at,
                    next_retry_at, attempt_count, started_at, build_version, schema_version,
                    eod_contract_version, created_at, updated_at
                ) values (?, 'FULL_MARKET', 'ALL', 'TRADING', 'LEDGER_FROZEN', 'FAILED',
                          1, 1, ?, ?, ?, 1, ?, 'build-test', 'schema-test', 'EOD_V1', ?, ?)
                """,
                BUSINESS_DATE,
                closeRunId,
                CLOSED_AT.plusMinutes(10),
                CLOSED_AT.plusMinutes(11),
                CLOSED_AT,
                CLOSED_AT,
                CLOSED_AT.plusSeconds(3)
        );
        Long cycleId = jdbcTemplate.queryForObject(
                "select id from stock_post_close_cycle where business_date = ? and scope_key = 'ALL'",
                Long.class,
                BUSINESS_DATE
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle_metric(
                    close_cycle_id, close_run_id, captured_open_order_count, cancelled_order_count,
                    released_buy_cash, released_sell_quantity,
                    settlement_target_account_count, account_snapshot_count, holding_snapshot_count,
                    price_snapshot_count, open_order_summary_count, reconciliation_mismatch_count,
                    settled_account_count, settlement_missing_account_count, updated_at
                ) values (?, ?, 12, 12, 147000.00, 4, 100, 105, 40, 3, 3, 0, 0, 100, ?)
                """,
                cycleId,
                closeRunId,
                CLOSED_AT.plusSeconds(3)
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_phase_attempt(
                    cycle_id, phase, attempt_no, owner_id, status, started_at,
                    build_version, schema_version, eod_contract_version, created_at, updated_at
                ) values (?, 'CLOSE_REQUESTED', 1, 'test-owner', 'COMPLETED', ?,
                          'build-test', 'schema-test', 'EOD_V1', ?, ?)
                """,
                cycleId,
                CLOSED_AT,
                CLOSED_AT,
                CLOSED_AT.plusSeconds(3)
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_readiness_check(
                    close_cycle_id, check_code, display_order, check_status,
                    failure_count, message, checked_at
                ) values (?, 'CORPORATE_CASH', 6, 'FAILED', 2, 'incomplete items=2', ?)
                """,
                cycleId,
                CLOSED_AT.plusMinutes(20)
        );

        var overview = service.overview();

        assertThat(overview)
                .extracting(
                        value -> value.businessState().activeBusinessDate(),
                        value -> value.businessState().rawSimulationDateTime(),
                        value -> value.marketState().orderEntryOpen(),
                        value -> value.cycle().phase(),
                        value -> value.cycle().eodContractVersion(),
                        value -> value.cycle().nextRetryAt(),
                        value -> value.metrics().capturedOpenOrderCount(),
                        value -> value.metrics().releasedBuyCash(),
                        value -> value.metrics().releasedSellQuantity(),
                        value -> value.metrics().settlementMissingAccountCount(),
                        value -> value.readinessChecks().getFirst().failureCount(),
                        value -> value.latestAttempt().status(),
                        value -> value.latestAttempt().eodContractVersion()
                )
                .containsExactly(
                        BUSINESS_DATE,
                        CLOSED_AT,
                        false,
                        "LEDGER_FROZEN",
                        "EOD_V1",
                        CLOSED_AT.plusMinutes(11),
                        12L,
                        new BigDecimal("147000.00"),
                        4L,
                        100L,
                        2L,
                        "COMPLETED",
                        "EOD_V1"
                );
    }

    @Test
    void overview_withoutCycle_returnsClockAndMarketStateOnly() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        EodOperationsOverviewService service = new EodOperationsOverviewService(JdbcClient.create(jdbcTemplate));
        seedBusinessState(jdbcTemplate);

        var overview = service.overview();

        assertThat(overview)
                .extracting(
                        value -> value.businessState().activeBusinessDate(),
                        value -> value.businessState().rawSimulationDateTime(),
                        value -> value.marketState().enabledSymbolCount(),
                        value -> value.cycle(),
                        value -> value.readinessChecks()
                )
                .containsExactly(BUSINESS_DATE, CLOSED_AT, 0, null, java.util.List.of());
    }

    @Test
    void overview_virtualMarketOpen_includesItInOrderEntryState() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        EodOperationsOverviewService service = new EodOperationsOverviewService(JdbcClient.create(jdbcTemplate));
        seedBusinessState(jdbcTemplate);
        jdbcTemplate.update(
                "insert into stock_virtual_market_config(symbol, enabled, market_status, updated_at) values ('VIRTUAL001', true, 'OPEN', ?)",
                CLOSED_AT
        );

        var overview = service.overview();

        assertThat(overview.marketState())
                .extracting(
                        value -> value.enabledSymbolCount(),
                        value -> value.openSymbolCount(),
                        value -> value.orderEntryOpen()
                )
                .containsExactly(1, 1, true);
    }

    private void seedBusinessState(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date,
                    raw_simulation_date, version, created_at, updated_at
                ) values ('DEFAULT', ?, ?, ?, 1, ?, ?)
                """,
                BUSINESS_DATE,
                BUSINESS_DATE.plusDays(1),
                BUSINESS_DATE,
                CLOSED_AT,
                CLOSED_AT
        );
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at,
                    last_heartbeat_at, updated_at
                ) values ('DEFAULT', ?, 7200, 5400, false, null, ?, ?)
                """,
                BUSINESS_DATE,
                CLOSED_AT,
                CLOSED_AT
        );
    }

    private JdbcTemplate createJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:eod-overview-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table stock_market_business_state(
                    state_id varchar(20) primary key,
                    active_business_date date not null,
                    preparing_business_date date,
                    raw_simulation_date date not null,
                    version bigint not null,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_simulation_clock(
                    clock_id varchar(20) primary key,
                    base_simulation_date date not null,
                    real_seconds_per_simulation_day int not null,
                    accumulated_real_seconds bigint not null,
                    running boolean not null,
                    last_started_at timestamp,
                    last_heartbeat_at timestamp,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_market_config(
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_virtual_market_config(
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_market_close_run(
                    id bigint generated by default as identity primary key,
                    symbol varchar(20),
                    business_date date not null,
                    closed_at timestamp not null,
                    status varchar(20) not null,
                    cancelled_order_count int not null,
                    holding_snapshot_count int not null,
                    price_rollover_count int not null,
                    created_at timestamp not null,
                    completed_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table stock_post_close_cycle(
                    id bigint generated by default as identity primary key,
                    business_date date not null,
                    scope_type varchar(20) not null,
                    scope_key varchar(40) not null,
                    cycle_kind varchar(20) not null,
                    skip_reason varchar(500),
                    phase varchar(60) not null,
                    status varchar(20) not null,
                    phase_revision int not null,
                    version bigint not null,
                    owner_id varchar(128),
                    lease_until timestamp,
                    next_retry_at timestamp,
                    close_run_id bigint,
                    settlement_eligible_at timestamp,
                    attempt_count int not null,
                    started_at timestamp,
                    completed_at timestamp,
                    last_error_code varchar(80),
                    last_error_message varchar(1000),
                    build_version varchar(100),
                    schema_version varchar(100),
                    eod_contract_version varchar(100) not null default 'UNDECLARED',
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_post_close_cycle_metric(
                    close_cycle_id bigint primary key,
                    close_run_id bigint,
                    captured_open_order_count bigint not null default 0,
                    cancelled_order_count bigint not null default 0,
                    released_buy_cash decimal(19,2) not null default 0.00,
                    released_sell_quantity bigint not null default 0,
                    settlement_target_account_count bigint not null default 0,
                    account_snapshot_count bigint not null default 0,
                    holding_snapshot_count bigint not null default 0,
                    price_snapshot_count bigint not null default 0,
                    open_order_summary_count bigint not null default 0,
                    reconciliation_mismatch_count bigint not null default 0,
                    settled_account_count bigint not null default 0,
                    settlement_missing_account_count bigint not null default 0,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_post_close_phase_attempt(
                    id bigint generated by default as identity primary key,
                    cycle_id bigint not null,
                    phase varchar(60) not null,
                    attempt_no int not null,
                    batch_job_execution_id bigint,
                    owner_id varchar(128) not null,
                    status varchar(20) not null,
                    started_at timestamp not null,
                    completed_at timestamp,
                    error_code varchar(80),
                    error_message varchar(1000),
                    build_version varchar(100),
                    schema_version varchar(100),
                    eod_contract_version varchar(100) not null default 'UNDECLARED',
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_post_close_readiness_check(
                    close_cycle_id bigint not null,
                    check_code varchar(60) not null,
                    display_order int not null,
                    check_status varchar(20) not null,
                    failure_count bigint not null,
                    message varchar(500),
                    checked_at timestamp not null,
                    primary key (close_cycle_id, check_code)
                )
                """);
        jdbcTemplate.execute("""
                create table stock_batch_job_signal(
                    id bigint generated by default as identity primary key,
                    signal_type varchar(60) not null,
                    job_name varchar(100) not null,
                    execution_mode varchar(120) not null,
                    status varchar(20) not null,
                    requested_at timestamp not null,
                    expected_cycle_id bigint,
                    eligible_at timestamp,
                    next_attempt_at timestamp not null,
                    attempt_count int not null,
                    max_attempts int not null,
                    processed_count int,
                    message varchar(500),
                    error_message varchar(1000),
                    completed_at timestamp
                )
                """);
        return jdbcTemplate;
    }
}
