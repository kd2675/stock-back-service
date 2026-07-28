package stock.back.service.market.biz;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.LiquidityProviderPolicyUpdateRequest;
import stock.back.service.market.vo.LiquidityProviderStatusChangeRequest;
import web.common.core.simulation.SimulationClockSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiquidityProviderControlServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime PRE_OPEN = BUSINESS_DATE.atTime(5, 0);

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private MarketLedgerFreezeGuard freezeGuard;
    private SimulationClockService simulationClockService;
    private LiquidityProviderControlService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:liquidity_control_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new FileSystemResource(batchH2Ddl())).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );

        simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentSnapshot()).thenReturn(clockSnapshot(PRE_OPEN));
        SimulationMarketSessionService marketSessionService =
                new SimulationMarketSessionService(simulationClockService, "06:00", "18:00");
        freezeGuard = mock(MarketLedgerFreezeGuard.class);
        when(freezeGuard.acquireJdbcMutationPermit(
                "liquidity-provider emergency suspension"
        )).thenReturn(BUSINESS_DATE);
        when(freezeGuard.acquireJdbcMutationPermit(
                "liquidity-provider policy scheduling"
        )).thenReturn(BUSINESS_DATE);
        when(freezeGuard.acquireJdbcMutationPermit(
                "liquidity-provider resume"
        )).thenReturn(BUSINESS_DATE);
        MarketRoleActivationDateService activationDateService =
                mock(MarketRoleActivationDateService.class);
        when(activationDateService.resolveNextOpeningDate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(BUSINESS_DATE.plusDays(1));

        service = new LiquidityProviderControlService(
                jdbcTemplate,
                new ObjectMapper(),
                simulationClockService,
                freezeGuard,
                activationDateService,
                new MarketRoleOrderCleanupService(jdbcTemplate)
        );
        seedMandate();
    }

    @Test
    void suspend_eodFreezeInProgress_leavesPolicyOrdersAndReservationsUnchanged() {
        seedOpenOrders();
        when(freezeGuard.acquireJdbcMutationPermit(
                "liquidity-provider emergency suspension"
        )).thenThrow(StockException.conflict("ledger freeze is in progress"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored ->
                service.suspend(
                        "DEMO001",
                        new LiquidityProviderStatusChangeRequest("동결 중 중단 요청"),
                        "stock-admin"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("ledger freeze is in progress");

        assertThat(jdbcTemplate.queryForMap(
                "select status, policy_version from stock_liquidity_mandate where id = 1"
        )).containsEntry("status", "ACTIVE")
                .containsEntry("policy_version", 3L);
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_order
                 where account_id = 101
                   and status = 'PENDING'
                """,
                Long.class
        )).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = 101",
                BigDecimal.class
        )).isEqualByComparingTo("499000.00");
        assertThat(jdbcTemplate.queryForObject(
                """
                select reserved_quantity
                  from stock_holding
                 where account_id = 101
                   and symbol = 'DEMO001'
                """,
                Long.class
        )).isEqualTo(20L);
    }

    @Test
    void resume_suspendedUnusedPreOpen_schedulesNextOpeningActivation() {
        jdbcTemplate.update(
                """
                update stock_liquidity_mandate
                   set status = 'SUSPENDED',
                       next_quote_at = null
                 where id = 1
                """
        );
        jdbcTemplate.update(
                """
                update stock_liquidity_daily_state
                   set submitted_buy_quantity = 0,
                       submitted_sell_quantity = 0,
                       executed_buy_quantity = 0,
                       executed_sell_quantity = 0
                 where simulation_trade_date = ?
                   and mandate_id = 1
                """,
                BUSINESS_DATE
        );
        jdbcTemplate.update(
                "update stock_liquidity_transition set stage = 'SUSPENDED' where mandate_id = 1"
        );

        transactionTemplate.executeWithoutResult(ignored ->
                service.resume(
                        "DEMO001",
                        new LiquidityProviderStatusChangeRequest("장전 운영 재개"),
                        "stock-admin"
                )
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select status, policy_version, next_quote_at
                  from stock_liquidity_mandate
                 where id = 1
                """
        )).containsEntry("status", "SUSPENDED")
                .containsEntry("policy_version", 3L)
                .containsEntry("next_quote_at", null);
        assertThat(jdbcTemplate.queryForMap(
                "select stage, policy_version from stock_liquidity_transition where mandate_id = 1"
        )).containsEntry("stage", "SUSPENDED")
                .containsEntry("policy_version", 3L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select version_no, effective_business_date, status, config_json
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'SCHEDULED'
                """
        )).containsEntry("version_no", 4L)
                .containsEntry(
                        "effective_business_date",
                        java.sql.Date.valueOf(BUSINESS_DATE.plusDays(1))
                )
                .containsEntry("status", "SCHEDULED");
        assertThat(jdbcTemplate.queryForObject(
                """
                select config_json
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'SCHEDULED'
                """,
                String.class
        )).contains("\"activationAction\":\"RESUME\"");
        verify(freezeGuard).acquireJdbcMutationPermit(
                "liquidity-provider resume"
        );
    }

    @Test
    void suspend_activeMandate_cancelsOnlyLpOrdersAndReturnsReservations() {
        seedOpenOrders();

        transactionTemplate.executeWithoutResult(ignored ->
                service.suspend(
                        "demo001",
                        new LiquidityProviderStatusChangeRequest("운영 점검"),
                        "stock-admin"
                )
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select status, policy_version, next_quote_at
                  from stock_liquidity_mandate
                 where id = 1
                """
        )).containsEntry("status", "SUSPENDED")
                .containsEntry("policy_version", 4L)
                .containsEntry("next_quote_at", null);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order where account_id = 101 and status = 'CANCELLED'",
                Long.class
        )).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "select coalesce(sum(reserved_cash), 0) from stock_order where account_id = 101",
                BigDecimal.class
        )).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = 101",
                BigDecimal.class
        )).isEqualByComparingTo("500000.00");
        assertThat(jdbcTemplate.queryForObject(
                "select reserved_quantity from stock_holding where account_id = 101 and symbol = 'DEMO001'",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForMap(
                """
                select cancelled_buy_quantity, cancelled_sell_quantity,
                       target_buy_open_quantity, target_sell_open_quantity,
                       state_status, gate_reason, policy_version
                  from stock_liquidity_daily_state
                 where simulation_trade_date = ?
                   and mandate_id = 1
                """,
                BUSINESS_DATE
        )).containsEntry("cancelled_buy_quantity", 10L)
                .containsEntry("cancelled_sell_quantity", 20L)
                .containsEntry("target_buy_open_quantity", 0L)
                .containsEntry("target_sell_open_quantity", 0L)
                .containsEntry("state_status", "HALTED")
                .containsEntry("gate_reason", "ADMIN_SUSPENDED")
                .containsEntry("policy_version", 4L);
        assertThat(jdbcTemplate.queryForMap(
                "select stage, policy_version from stock_liquidity_transition where mandate_id = 1"
        )).containsEntry("stage", "SUSPENDED")
                .containsEntry("policy_version", 4L);
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and version_no = 4
                   and status = 'ACTIVE'
                   and config_json like '%SUSPENDED%'
                """,
                Long.class
        )).isOne();
    }

    @Test
    void updatePolicy_activeUsedRegularSession_schedulesNextPolicyWithoutChangingCurrentState() {
        when(simulationClockService.currentSnapshot()).thenReturn(
                clockSnapshot(BUSINESS_DATE.atTime(12, 0), true)
        );
        jdbcTemplate.update(
                """
                update stock_liquidity_daily_state
                   set submitted_buy_quantity = 100,
                       executed_buy_quantity = 50
                 where simulation_trade_date = ?
                   and mandate_id = 1
                """,
                BUSINESS_DATE
        );
        LiquidityProviderPolicyUpdateRequest request =
                new LiquidityProviderPolicyUpdateRequest(
                        3,
                        10,
                        150L,
                        30_000L,
                        new BigDecimal("0.040000"),
                        new BigDecimal("0.070000"),
                        new BigDecimal("0.005000"),
                        5,
                        new BigDecimal("0.080000"),
                        new BigDecimal("0.080000"),
                        new BigDecimal("2.0000"),
                        1_200L,
                        300L,
                        4,
                        5,
                        1,
                        60,
                        3,
                        600,
                        30,
                        new BigDecimal("5000.00"),
                        "재고 밴드 조정"
                );

        transactionTemplate.executeWithoutResult(ignored ->
                service.updatePolicy("DEMO001", request, "stock-admin")
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select status, target_spread_ticks, max_spread_ticks,
                       max_order_quantity, reference_daily_volume,
                       target_inventory_quantity, inventory_band_quantity,
                       daily_loss_limit_amount, passive_only, policy_version
                  from stock_liquidity_mandate
                 where id = 1
                """
        )).containsEntry("status", "ACTIVE")
                .containsEntry("target_spread_ticks", 4)
                .containsEntry("max_spread_ticks", 12)
                .containsEntry("max_order_quantity", 100L)
                .containsEntry("reference_daily_volume", 20_000L)
                .containsEntry("target_inventory_quantity", 1_000L)
                .containsEntry("inventory_band_quantity", 200L)
                .containsEntry("daily_loss_limit_amount", new BigDecimal("10000.00"))
                .containsEntry("passive_only", true)
                .containsEntry("policy_version", 3L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select version_no, effective_business_date, status,
                       change_reason, changed_by
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'SCHEDULED'
                """
        )).containsEntry("version_no", 4L)
                .containsEntry(
                        "effective_business_date",
                        java.sql.Date.valueOf(BUSINESS_DATE.plusDays(1))
                )
                .containsEntry("status", "SCHEDULED")
                .containsEntry("change_reason", "재고 밴드 조정")
                .containsEntry("changed_by", "stock-admin");
        assertThat(jdbcTemplate.queryForObject(
                """
                select config_json
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'SCHEDULED'
                """,
                String.class
        )).contains("\"referenceDailyVolume\":30000");
        assertThat(jdbcTemplate.queryForMap(
                """
                select submitted_buy_quantity, executed_buy_quantity,
                       state_status, gate_reason, policy_version
                  from stock_liquidity_daily_state
                 where simulation_trade_date = ?
                   and mandate_id = 1
                """,
                BUSINESS_DATE
        )).containsEntry("submitted_buy_quantity", 100L)
                .containsEntry("executed_buy_quantity", 50L)
                .containsEntry("state_status", "QUOTING")
                .containsEntry("gate_reason", "WITHIN_LIMITS")
                .containsEntry("policy_version", 3L);
        verify(freezeGuard).acquireJdbcMutationPermit(
                "liquidity-provider policy scheduling"
        );
    }

    @Test
    void updatePolicy_existingSchedule_replacesDraftWithoutAddingAnotherVersion() {
        LiquidityProviderPolicyUpdateRequest request =
                new LiquidityProviderPolicyUpdateRequest(
                        3, 10, 150L, 30_000L,
                        new BigDecimal("0.040000"),
                        new BigDecimal("0.070000"),
                        new BigDecimal("0.005000"),
                        5,
                        new BigDecimal("0.080000"),
                        new BigDecimal("0.080000"),
                        new BigDecimal("2.0000"),
                        1_200L, 300L, 4, 5, 1,
                        60, 3, 600, 30,
                        new BigDecimal("5000.00"),
                        "첫 예약"
                );
        transactionTemplate.executeWithoutResult(ignored ->
                service.updatePolicy("DEMO001", request, "stock-admin")
        );
        LiquidityProviderPolicyUpdateRequest replacement =
                new LiquidityProviderPolicyUpdateRequest(
                        5, 12, 100L, 25_000L,
                        new BigDecimal("0.030000"),
                        new BigDecimal("0.060000"),
                        new BigDecimal("0.004000"),
                        4,
                        new BigDecimal("0.070000"),
                        new BigDecimal("0.070000"),
                        new BigDecimal("1.5000"),
                        900L, 250L, 3, 4, 1,
                        90, 4, 900, 45,
                        new BigDecimal("4000.00"),
                        "예약 교체"
                );

        transactionTemplate.executeWithoutResult(ignored ->
                service.updatePolicy("DEMO001", replacement, "stock-admin-2")
        );

        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'SCHEDULED'
                """,
                Long.class
        )).isOne();
        assertThat(jdbcTemplate.queryForMap(
                """
                select version_no, change_reason, changed_by, config_json
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'SCHEDULED'
                """
        )).containsEntry("version_no", 4L)
                .containsEntry("change_reason", "예약 교체")
                .containsEntry("changed_by", "stock-admin-2");
        assertThat(jdbcTemplate.queryForObject(
                """
                select config_json
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'SCHEDULED'
                """,
                String.class
        )).contains("\"referenceDailyVolume\":25000");
    }

    @Test
    void scheduledPolicy_suspendAndResume_rebasesPendingVersionWithoutLosingDraft() {
        LiquidityProviderPolicyUpdateRequest request =
                new LiquidityProviderPolicyUpdateRequest(
                        3, 10, 150L, 30_000L,
                        new BigDecimal("0.040000"),
                        new BigDecimal("0.070000"),
                        new BigDecimal("0.005000"),
                        5,
                        new BigDecimal("0.080000"),
                        new BigDecimal("0.080000"),
                        new BigDecimal("2.0000"),
                        1_200L, 300L, 4, 5, 1,
                        60, 3, 600, 30,
                        new BigDecimal("5000.00"),
                        "다음 장 정책"
                );
        transactionTemplate.executeWithoutResult(ignored ->
                service.updatePolicy("DEMO001", request, "stock-admin")
        );

        transactionTemplate.executeWithoutResult(ignored ->
                service.suspend(
                        "DEMO001",
                        new LiquidityProviderStatusChangeRequest("일시 중단"),
                        "stock-admin"
                )
        );
        transactionTemplate.executeWithoutResult(ignored ->
                service.resume(
                        "DEMO001",
                        new LiquidityProviderStatusChangeRequest("장전 재개"),
                        "stock-admin"
                )
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select status, policy_version
                  from stock_liquidity_mandate
                 where id = 1
                """
        )).containsEntry("status", "SUSPENDED")
                .containsEntry("policy_version", 4L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select version_no, effective_business_date,
                       change_reason, changed_by, config_json
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'SCHEDULED'
                """
        )).containsEntry("version_no", 5L)
                .containsEntry(
                        "effective_business_date",
                        java.sql.Date.valueOf(BUSINESS_DATE.plusDays(1))
                )
                .containsEntry("change_reason", "장전 재개")
                .containsEntry("changed_by", "stock-admin");
        assertThat(jdbcTemplate.queryForObject(
                """
                select config_json
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'SCHEDULED'
                """,
                String.class
        )).contains("\"referenceDailyVolume\":30000")
                .contains("\"activationAction\":\"RESUME\"");
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and status = 'ACTIVE'
                """,
                Long.class
        )).isOne();
    }

    private void seedMandate() {
        jdbcTemplate.update(
                """
                insert into stock_market_participant(
                    id, participant_code, display_name, participant_type,
                    status, self_trade_group_id, created_at, updated_at
                ) values (
                    11, 'LP-ONE', '축소시장 LP', 'LIQUIDITY_PROVIDER',
                    'ACTIVE', 'LP:ONE', ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    101, 'lp-demo001', 'LP-DEMO001', 'ACTIVE',
                    'LIQUIDITY_PROVIDER', 'LP:ONE', 499000, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_market_participant_account(
                    participant_id, account_id, account_role, desk_code,
                    effective_from, effective_to, status, created_at, updated_at
                ) values (
                    11, 101, 'LIQUIDITY_PROVIDER', 'DEMO001',
                    ?, null, 'ACTIVE', ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1),
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares,
                    tradable_shares, tick_size, price_limit_rate,
                    enabled, created_at, updated_at
                ) values (
                    'DEMO001', '테스트 종목', 'ORDER_BOOK', 100,
                    2000000, 1000000, 1, 30, true, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_price(
                    symbol, current_price, previous_close, price_time, provider
                ) values ('DEMO001', 120, 115, ?, 'test')
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (101, 'DEMO001', 1000, 0, 110, ?)
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_liquidity_mandate(
                    id, participant_id, account_id, symbol, mandate_code,
                    execution_mode, status, contract_start_date,
                    max_order_quantity, reference_daily_volume,
                    target_inventory_quantity, inventory_band_quantity,
                    daily_loss_limit_amount, next_quote_at, policy_version,
                    created_at, updated_at
                ) values (
                    1, 11, 101, 'DEMO001', 'LP-DEMO001',
                    'LIVE', 'ACTIVE', ?, 100, 20000,
                    1000, 200, 10000, ?, 3, ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1),
                PRE_OPEN.plusHours(1),
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_liquidity_daily_state(
                    simulation_trade_date, mandate_id,
                    reference_daily_volume, execution_quantity_limit,
                    submission_quantity_limit, target_buy_open_quantity,
                    target_sell_open_quantity, last_open_buy_quantity,
                    last_open_sell_quantity, last_inventory_quantity,
                    last_projected_inventory_quantity, state_status,
                    gate_reason, policy_version, created_at, updated_at
                ) values (
                    ?, 1, 20000, 2000, 4000, 10, 20, 10, 20,
                    1000, 1000, 'QUOTING', 'WITHIN_LIMITS', 3, ?, ?
                )
                """,
                BUSINESS_DATE,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_liquidity_transition(
                    transition_key, symbol, mandate_id, participant_id,
                    liquidity_account_id, source_account_id, legacy_account_id,
                    stage, reference_daily_volume, seed_inventory_quantity,
                    seed_cash_amount, effective_business_date,
                    legacy_disabled_at, activated_at,
                    requested_by, change_reason, policy_version,
                    created_at, updated_at
                ) values (
                    'LIQUIDITY-TRANSITION:DEMO001', 'DEMO001', 1, 11,
                    101, 201, 201, 'LIVE_ACTIVE', 20000, 1000,
                    500000, ?, ?, ?, 'admin-test', '축소 시장 LP 준비', 3,
                    ?, ?
                )
                """,
                BUSINESS_DATE,
                PRE_OPEN,
                PRE_OPEN,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no,
                    effective_business_date, status, config_json,
                    change_reason, changed_by, created_at, updated_at
                ) values (
                    'LIQUIDITY_MANDATE', 'DEMO001', 3, ?,
                    'ACTIVE', '{}', '초기 정책', 'admin-test', ?, ?
                )
                """,
                BUSINESS_DATE,
                PRE_OPEN,
                PRE_OPEN
        );
    }

    private void seedOpenOrders() {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    'lp-buy', 101, 'LIQUIDITY_PROVIDER', 'LP:ONE',
                    'DEMO001', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 100,
                    10, 0, 1000, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    'lp-sell', 101, 'LIQUIDITY_PROVIDER', 'LP:ONE',
                    'DEMO001', 'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING', 121,
                    20, 0, 0, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = 20
                 where account_id = 101
                   and symbol = 'DEMO001'
                """
        );
    }

    private SimulationClockSnapshot clockSnapshot(LocalDateTime dateTime) {
        return clockSnapshot(dateTime, false);
    }

    private SimulationClockSnapshot clockSnapshot(
            LocalDateTime dateTime,
            boolean running
    ) {
        return new SimulationClockSnapshot(
                BUSINESS_DATE,
                dateTime,
                BUSINESS_DATE.atStartOfDay(),
                dateTime,
                BUSINESS_DATE.atStartOfDay(),
                7_200,
                running,
                false,
                0L,
                null,
                null
        );
    }

    private Path batchH2Ddl() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path moduleRelative = workingDirectory
                .resolve("../stock-batch-service/src/main/resources/db/ddl/stock_h2.sql")
                .normalize();
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        Path rootRelative = workingDirectory
                .resolve("stock-batch-service/src/main/resources/db/ddl/stock_h2.sql")
                .normalize();
        assertThat(rootRelative).isRegularFile();
        return rootRelative;
    }
}
