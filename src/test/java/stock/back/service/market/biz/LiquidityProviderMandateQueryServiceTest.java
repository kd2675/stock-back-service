package stock.back.service.market.biz;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import stock.back.service.market.vo.LiquidityProviderMandateResponse;
import web.common.core.simulation.SimulationClockSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiquidityProviderMandateQueryServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = BUSINESS_DATE.atTime(12, 0);

    private JdbcTemplate jdbcTemplate;
    private LiquidityProviderMandateQueryService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:liquidity_mandate_query_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new FileSystemResource(batchH2Ddl())).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentSnapshot()).thenReturn(clockSnapshot());
        service = new LiquidityProviderMandateQueryService(
                JdbcClient.create(dataSource),
                simulationClockService,
                new ObjectMapper()
        );
        seedLiquidityMandate();
    }

    @Test
    void getMandates_currentTradeDate_exposesRolePolicyStateAndTransitionAudit() {
        List<LiquidityProviderMandateResponse> result = service.getMandates();

        assertThat(result).singleElement().satisfies(mandate -> {
            assertThat(mandate.mandateCode()).isEqualTo("LP-DEMO001");
            assertThat(mandate.executionMode()).isEqualTo("LIVE");
            assertThat(mandate.simulationTradeDate()).isEqualTo(BUSINESS_DATE);
            assertThat(mandate.roleEligible()).isTrue();
            assertThat(mandate.roleEligibilityIssue()).isNull();
            assertThat(mandate.account().participantSelfTradeGroupId()).isEqualTo("LP:ONE");
            assertThat(mandate.account().accountSelfTradeGroupId()).isEqualTo("LP:ONE");
            assertThat(mandate.account().holdingQuantity()).isEqualTo(1_000L);
            assertThat(mandate.account().reservedSellQuantity()).isEqualTo(100L);
            assertThat(mandate.account().availableSellQuantity()).isEqualTo(900L);
            assertThat(mandate.account().holdingMarketValue())
                    .isEqualByComparingTo("120000.00");
            assertThat(mandate.policy().referenceDailyVolume()).isEqualTo(20_000L);
            assertThat(mandate.policy().targetOpenParticipationRate())
                    .isEqualByComparingTo("0.050000");
            assertThat(mandate.scheduledPolicy()).isNull();
            assertThat(mandate.transition()).isNotNull();
            assertThat(mandate.transition().transitionKey())
                    .isEqualTo("LIQUIDITY-TRANSITION:DEMO001");
            assertThat(mandate.transition().stage()).isEqualTo("LIVE_ACTIVE");
            assertThat(mandate.transition().sourceAccountId()).isEqualTo(201L);
            assertThat(mandate.transition().legacyAccountId()).isEqualTo(201L);
            assertThat(mandate.transition().referenceDailyVolume()).isEqualTo(20_000L);
            assertThat(mandate.transition().seedInventoryQuantity()).isEqualTo(1_000L);
            assertThat(mandate.transition().seedCashAmount())
                    .isEqualByComparingTo("500000.00");
            assertThat(mandate.transition().transferredInventoryQuantity()).isZero();
            assertThat(mandate.transition().transferredCashAmount())
                    .isEqualByComparingTo("0.00");
            assertThat(mandate.transition().legacyRetiredAt()).isNull();
            assertThat(mandate.transition().effectiveBusinessDate())
                    .isEqualTo(BUSINESS_DATE);
            assertThat(mandate.transition().activatedAt()).isEqualTo(NOW);
            assertThat(mandate.transition().policyVersion()).isEqualTo(1L);
            assertThat(mandate.dailyState()).isNotNull();
            assertThat(mandate.dailyState().stateStatus()).isEqualTo("QUOTING");
            assertThat(mandate.dailyState().gateReason()).isEqualTo("WITHIN_LIMITS");
            assertThat(mandate.dailyState().openingNetAssetValue())
                    .isEqualByComparingTo("620000.00");
            assertThat(mandate.dailyState().currentNetAssetValue())
                    .isEqualByComparingTo("625000.00");
            assertThat(mandate.dailyState().riskProfit())
                    .isEqualByComparingTo("5000.00");
        });
    }

    @Test
    void getMandates_scheduledPolicy_exposesCurrentAndNextPolicySeparately() {
        jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no,
                    effective_business_date, status, config_json,
                    change_reason, changed_by, created_at, updated_at
                ) values (
                    'LIQUIDITY_MANDATE', 'DEMO001', 4, ?,
                    'SCHEDULED', ?,
                    '다음 거래일 호가 축소', 'stock-admin', ?, ?
                )
                """,
                BUSINESS_DATE.plusDays(1),
                """
                {
                  "symbol":"DEMO001",
                  "executionMode":"LIVE",
                  "targetSpreadTicks":5,
                  "maxSpreadTicks":14,
                  "maxOrderQuantity":80,
                  "referenceDailyVolume":15000,
                  "targetOpenParticipationRate":0.04,
                  "maxOpenParticipationRate":0.07,
                  "maxSingleOrderParticipationRate":0.005,
                  "externalDepthLevels":5,
                  "maxExternalDepthParticipationRate":0.08,
                  "dailyExecutionParticipationRate":0.08,
                  "dailySubmissionMultiplier":2.0,
                  "targetInventoryQuantity":900,
                  "inventoryBandQuantity":150,
                  "inventorySkewTicks":4,
                  "primaryRegimeWeight":0.7,
                  "liquiditySizeSensitivity":0.25,
                  "volatilitySpreadMaxTicks":5,
                  "priceRegimeMaxSkewTicks":1,
                  "passiveOnly":true,
                  "minimumQuoteLifetimeSeconds":60,
                  "repriceThresholdTicks":3,
                  "orderTtlSeconds":600,
                  "quoteIntervalSeconds":30,
                  "dailyLossLimitAmount":5000
                }
                """,
                NOW,
                NOW
        );

        LiquidityProviderMandateResponse mandate = service.getMandates().getFirst();

        assertThat(mandate.policyVersion()).isEqualTo(3L);
        assertThat(mandate.policy().referenceDailyVolume()).isEqualTo(20_000L);
        assertThat(mandate.scheduledPolicy()).isNotNull();
        assertThat(mandate.scheduledPolicy().policyVersion()).isEqualTo(4L);
        assertThat(mandate.scheduledPolicy().effectiveBusinessDate())
                .isEqualTo(BUSINESS_DATE.plusDays(1));
        assertThat(mandate.scheduledPolicy().policy().referenceDailyVolume())
                .isEqualTo(15_000L);
        assertThat(mandate.scheduledPolicy().changeReason())
                .isEqualTo("다음 거래일 호가 축소");
        assertThat(mandate.scheduledPolicy().changedBy()).isEqualTo("stock-admin");
    }

    @Test
    void getMandates_nullOriginOpenOrder_marksDedicatedAccountIneligible() {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash,
                    created_at, updated_at
                ) values (
                    'legacy-null-origin', 101, null, 'LP:ONE',
                    'DEMO001', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 119,
                    10, 0, 1190, ?, ?
                )
                """,
                NOW,
                NOW
        );

        LiquidityProviderMandateResponse mandate = service.getMandates().getFirst();

        assertThat(mandate.roleEligible()).isFalse();
        assertThat(mandate.roleEligibilityIssue())
                .isEqualTo("NON_LP_OPEN_ORDER_ON_DEDICATED_ACCOUNT");
        assertThat(mandate.account().nonLiquidityOpenOrderCount()).isEqualTo(1L);
    }

    @Test
    void getMandates_virtualPriceOrder_marksDedicatedAccountIneligible() {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash,
                    created_at, updated_at
                ) values (
                    'wrong-market-order', 101, 'LIQUIDITY_PROVIDER', 'LP:ONE',
                    'DEMO001', 'VIRTUAL_PRICE', 'BUY', 'LIMIT', 'PENDING', 119,
                    10, 0, 1190, ?, ?
                )
                """,
                NOW,
                NOW
        );

        LiquidityProviderMandateResponse mandate = service.getMandates().getFirst();

        assertThat(mandate.roleEligible()).isFalse();
        assertThat(mandate.roleEligibilityIssue())
                .isEqualTo("NON_LP_OPEN_ORDER_ON_DEDICATED_ACCOUNT");
        assertThat(mandate.account().nonLiquidityOpenOrderCount()).isEqualTo(1L);
    }

    @Test
    void getMandates_unmanagedHolding_marksDedicatedAccountIneligible() {
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (101, 'DEMO999', 10, 0, 100, ?)
                """,
                NOW
        );

        LiquidityProviderMandateResponse mandate = service.getMandates().getFirst();

        assertThat(mandate.roleEligible()).isFalse();
        assertThat(mandate.roleEligibilityIssue())
                .isEqualTo("UNMANAGED_HOLDING_ON_DEDICATED_ACCOUNT");
        assertThat(mandate.account().unmanagedHoldingCount()).isEqualTo(1L);
    }

    @Test
    void getMandates_roleDeskSymbolMismatch_matchesBatchEligibility() {
        jdbcTemplate.update(
                """
                update stock_market_participant_account
                   set desk_code = 'DEMO999'
                 where participant_id = 11
                   and account_id = 101
                """
        );

        LiquidityProviderMandateResponse mandate = service.getMandates().getFirst();

        assertThat(mandate.roleEligible()).isFalse();
        assertThat(mandate.roleEligibilityIssue())
                .isEqualTo("ROLE_DESK_SYMBOL_MISMATCH");
    }

    @Test
    void getMandates_lpOrderWithoutMatchingStrategyOrigin_marksAccountIneligible() {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash,
                    created_at, updated_at
                ) values (
                    'lp-without-strategy-origin', 101, 'LIQUIDITY_PROVIDER', 'LP:ONE',
                    'DEMO001', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 119,
                    10, 0, 1190, ?, ?
                )
                """,
                NOW,
                NOW
        );

        LiquidityProviderMandateResponse mandate = service.getMandates().getFirst();

        assertThat(mandate.roleEligible()).isFalse();
        assertThat(mandate.roleEligibilityIssue())
                .isEqualTo("NON_LP_OPEN_ORDER_ON_DEDICATED_ACCOUNT");
        assertThat(mandate.account().nonLiquidityOpenOrderCount()).isEqualTo(1L);
    }

    @Test
    void getMandates_lpOrderWithMatchingStrategyOrigin_remainsEligible() {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash,
                    created_at, updated_at
                ) values (
                    'lp-with-strategy-origin', 101, 'LIQUIDITY_PROVIDER', 'LP:ONE',
                    'DEMO001', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 119,
                    10, 0, 1190, ?, ?
                )
                """,
                NOW,
                NOW
        );
        Long orderId = jdbcTemplate.queryForObject(
                "select id from stock_order where client_order_id = 'lp-with-strategy-origin'",
                Long.class
        );
        jdbcTemplate.update(
                """
                insert into stock_order_strategy_origin(
                    order_id, origin_type, participant_id,
                    liquidity_mandate_id, policy_version, created_at
                ) values (?, 'LIQUIDITY_PROVIDER', 11, 1, 3, ?)
                """,
                orderId,
                NOW
        );

        LiquidityProviderMandateResponse mandate = service.getMandates().getFirst();

        assertThat(mandate.roleEligible()).isTrue();
        assertThat(mandate.roleEligibilityIssue()).isNull();
        assertThat(mandate.account().nonLiquidityOpenOrderCount()).isZero();
    }

    private void seedLiquidityMandate() {
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
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    101, 'lp-demo001', 'LP-DEMO001', 'ACTIVE', 'LIQUIDITY_PROVIDER',
                    'LP:ONE', 500000, ?, ?
                )
                """,
                NOW,
                NOW
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
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares, tradable_shares,
                    tick_size, price_limit_rate, enabled, created_at, updated_at
                ) values (
                    'DEMO001', '테스트 종목', 'ORDER_BOOK', 100,
                    2000000, 1000000, 1, 30, true, ?, ?
                )
                """,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_price(
                    symbol, current_price, previous_close, price_time, provider
                ) values ('DEMO001', 120, 115, ?, 'test')
                """,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (101, 'DEMO001', 1000, 100, 110, ?)
                """,
                NOW
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
                NOW.plusSeconds(30),
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_liquidity_daily_state(
                    simulation_trade_date, mandate_id,
                    reference_daily_volume, execution_quantity_limit,
                    submission_quantity_limit, submitted_buy_quantity,
                    submitted_sell_quantity, executed_buy_quantity,
                    executed_sell_quantity, opening_net_asset_value,
                    current_net_asset_value, risk_profit,
                    target_buy_open_quantity, target_sell_open_quantity,
                    last_open_buy_quantity, last_open_sell_quantity,
                    external_buy_depth_quantity, external_sell_depth_quantity,
                    last_bid_price, last_ask_price,
                    last_inventory_quantity, last_projected_inventory_quantity,
                    blended_price_pressure, blended_volatility_pressure,
                    blended_liquidity_pressure, state_status, gate_reason,
                    quote_run_count, limit_breached, policy_version, version,
                    created_at, updated_at
                ) values (
                    ?, 1, 20000, 2000, 4000, 100, 120, 20, 30,
                    620000, 625000, 5000, 500, 500, 400, 450,
                    3000, 2800, 119, 121, 1000, 1050,
                    -0.10, 0.20, -0.30, 'QUOTING', 'WITHIN_LIMITS',
                    2, false, 3, 1, ?, ?
                )
                """,
                BUSINESS_DATE,
                NOW,
                NOW
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
                    500000, ?, ?, ?, 'admin-test', '축소 시장 LP 준비', 1,
                    ?, ?
                )
                """,
                BUSINESS_DATE,
                NOW,
                NOW,
                NOW,
                NOW
        );
    }

    private SimulationClockSnapshot clockSnapshot() {
        return new SimulationClockSnapshot(
                BUSINESS_DATE,
                NOW,
                BUSINESS_DATE.atStartOfDay(),
                NOW,
                BUSINESS_DATE.atStartOfDay(),
                7_200,
                false,
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
