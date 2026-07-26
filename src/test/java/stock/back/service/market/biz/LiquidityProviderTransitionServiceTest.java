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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.market.vo.LiquidityProviderActivationRequest;
import stock.back.service.market.vo.LiquidityProviderProvisionRequest;
import stock.back.service.trading.biz.AccountOrderCleanupService;
import web.common.core.simulation.SimulationClockSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiquidityProviderTransitionServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime PRE_OPEN = BUSINESS_DATE.atTime(5, 0);

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private SimulationClockService simulationClockService;
    private MarketLedgerFreezeGuard freezeGuard;
    private StockAccountRepository stockAccountRepository;
    private AccountOrderCleanupService accountOrderCleanupService;
    private LiquidityProviderTransitionService service;
    private LiquidityProviderRecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:liquidity_transition_" + UUID.randomUUID()
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
        when(simulationClockService.currentSnapshot()).thenReturn(clockSnapshot(false, PRE_OPEN));
        when(simulationClockService.currentMarketDateTime()).thenReturn(PRE_OPEN);
        SimulationMarketSessionService marketSessionService =
                new SimulationMarketSessionService(simulationClockService, "06:00", "18:00");
        freezeGuard = mock(MarketLedgerFreezeGuard.class);
        when(freezeGuard.acquireMutationPermit("liquidity-provider shadow provisioning"))
                .thenReturn(BUSINESS_DATE);
        when(freezeGuard.acquireMutationPermit("liquidity-provider live activation"))
                .thenReturn(BUSINESS_DATE);
        stockAccountRepository = mock(StockAccountRepository.class);
        accountOrderCleanupService = mock(AccountOrderCleanupService.class);
        service = new LiquidityProviderTransitionService(
                jdbcTemplate,
                new ObjectMapper(),
                simulationClockService,
                marketSessionService,
                freezeGuard,
                stockAccountRepository,
                accountOrderCleanupService
        );
        recommendationService = new LiquidityProviderRecommendationService(
                JdbcClient.create(dataSource)
        );
        seedLegacySymbol();
    }

    @Test
    void provisionShadow_defaultRates_separatesInventoryAndCreatesNoOrders() {
        transactionTemplate.executeWithoutResult(status ->
                service.provisionShadow("demo001", null, "stock-admin"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select enabled from stock_listing_auto_account_config where symbol = 'DEMO001'",
                Boolean.class
        )).isTrue();
        assertThat(jdbcTemplate.queryForMap(
                """
                select mandate.execution_mode, mandate.reference_daily_volume,
                       mandate.max_order_quantity, mandate.target_inventory_quantity,
                       mandate.inventory_band_quantity, transition.stage,
                       transition.seed_inventory_quantity, transition.seed_cash_amount
                  from stock_liquidity_mandate mandate
                  join stock_liquidity_transition transition
                    on transition.mandate_id = mandate.id
                 where mandate.symbol = 'DEMO001'
                """
        )).containsEntry("execution_mode", "SHADOW")
                .containsEntry("reference_daily_volume", 30_000L)
                .containsEntry("max_order_quantity", 300L)
                .containsEntry("target_inventory_quantity", 5_000L)
                .containsEntry("inventory_band_quantity", 5_000L)
                .containsEntry("stage", "SHADOW_READY")
                .containsEntry("seed_inventory_quantity", 5_000L)
                .containsEntry("seed_cash_amount", new BigDecimal("500000.00"));
        assertThat(jdbcTemplate.queryForObject(
                "select quantity from stock_holding where account_id = 100 and symbol = 'DEMO001'",
                Long.class
        )).isEqualTo(995_000L);
        assertThat(jdbcTemplate.queryForObject(
                """
                select holding.quantity
                  from stock_holding holding
                  join stock_account account on account.id = holding.account_id
                 where account.participant_category = 'LIQUIDITY_PROVIDER'
                   and holding.symbol = 'DEMO001'
                """,
                Long.class
        )).isEqualTo(5_000L);
        assertThat(jdbcTemplate.queryForObject(
                "select coalesce(sum(quantity), 0) from stock_holding where symbol = 'DEMO001'",
                Long.class
        )).isEqualTo(1_000_000L);
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_security_allocation_ledger
                 where symbol = 'DEMO001'
                   and allocation_reason = 'LIQUIDITY_SEED_TRANSFER'
                """,
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_account_cash_flow flow
                  join stock_account account on account.id = flow.account_id
                 where account.participant_category = 'LIQUIDITY_PROVIDER'
                   and flow.reason = 'OPENING_GRANT'
                """,
                Integer.class
        )).isOne();
    }

    @Test
    void activateLive_pausedPreOpen_cleansLegacyThenSwitchesAtomically() {
        transactionTemplate.executeWithoutResult(status ->
                service.provisionShadow("DEMO001", null, "stock-admin"));
        StockAccount legacyAccount = StockAccount.open("stock-listing-demo001");
        ReflectionTestUtils.setField(legacyAccount, "id", 100L);
        when(stockAccountRepository.findAllByIdInForUpdate(List.of(100L)))
                .thenReturn(List.of(legacyAccount));

        transactionTemplate.executeWithoutResult(status ->
                service.activateLive(
                        "DEMO001",
                        new LiquidityProviderActivationRequest("pilot review passed"),
                        "stock-admin"
                ));

        verify(accountOrderCleanupService)
                .cancelOpenOrderBookOrders(legacyAccount, "DEMO001");
        assertThat(jdbcTemplate.queryForObject(
                "select enabled from stock_listing_auto_account_config where symbol = 'DEMO001'",
                Boolean.class
        )).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select execution_mode from stock_liquidity_mandate where symbol = 'DEMO001'",
                String.class
        )).isEqualTo("LIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select stage from stock_liquidity_transition where symbol = 'DEMO001'",
                String.class
        )).isEqualTo("LIVE_ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and version_no = 2
                   and status = 'ACTIVE'
                """,
                Integer.class
        )).isOne();
    }

    @Test
    void activateLive_pendingRoleSeparatedListing_enablesMarketForNextSession() {
        convertFixtureToPendingRoleSeparatedListing();
        transactionTemplate.executeWithoutResult(status ->
                service.provisionShadow("DEMO001", null, "stock-admin"));

        assertThat(jdbcTemplate.queryForMap(
                """
                select enabled, market_status
                  from stock_order_book_market_config
                 where symbol = 'DEMO001'
                """
        )).containsEntry("enabled", false)
                .containsEntry("market_status", "CLOSED");

        transactionTemplate.executeWithoutResult(status ->
                service.activateLive(
                        "DEMO001",
                        new LiquidityProviderActivationRequest("new listing LP ready"),
                        "stock-admin"
                ));

        verify(accountOrderCleanupService, never())
                .cancelOpenOrderBookOrders(
                        org.mockito.ArgumentMatchers.any(StockAccount.class),
                        org.mockito.ArgumentMatchers.anyString()
                );
        assertThat(jdbcTemplate.queryForMap(
                """
                select enabled, market_status
                  from stock_order_book_market_config
                 where symbol = 'DEMO001'
                """
        )).containsEntry("enabled", true)
                .containsEntry("market_status", "CLOSED");
        assertThat(jdbcTemplate.queryForObject(
                "select execution_mode from stock_liquidity_mandate where symbol = 'DEMO001'",
                String.class
        )).isEqualTo("LIVE");
    }

    @Test
    void provisionShadow_multipleContractsForSameUnderwriter_resolvesOneSourceAccount() {
        convertFixtureToPendingRoleSeparatedListing();
        Long participantId = jdbcTemplate.queryForObject(
                """
                select id
                  from stock_market_participant
                 where participant_code = 'DEFAULT_ISSUE_UNDERWRITER'
                """,
                Long.class
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_contract(
                    contract_code, corporate_action_id, symbol,
                    participant_id, account_id, total_issue_quantity,
                    tradable_allocation_quantity, locked_allocation_quantity,
                    external_allocation_quantity, underwritten_quantity,
                    issue_price, underwriting_type, status, policy_version,
                    created_at, updated_at
                ) values (
                    'UW-SECONDARY-DEMO001', null, 'DEMO001',
                    ?, 100, 1000000,
                    1000000, 0,
                    0, 1000000,
                    100, 'FIRM_COMMITMENT', 'COMPLETED', 1,
                    ?, ?
                )
                """,
                participantId,
                PRE_OPEN,
                PRE_OPEN
        );

        var recommendation = recommendationService.getRecommendation();

        assertThat(recommendation.recommendedProviderCount()).isEqualTo(1);
        assertThat(recommendation.symbols()).singleElement().satisfies(symbol -> {
            assertThat(symbol.symbol()).isEqualTo("DEMO001");
            assertThat(symbol.recommendedSourceAccountId()).isEqualTo(100L);
            assertThat(symbol.creationEligible()).isTrue();
        });

        transactionTemplate.executeWithoutResult(status ->
                service.provisionShadow("DEMO001", null, "stock-admin"));

        assertThat(jdbcTemplate.queryForObject(
                """
                select source_account_id
                  from stock_liquidity_transition
                 where symbol = 'DEMO001'
                """,
                Long.class
        )).isEqualTo(100L);
    }

    @Test
    void activateLive_selfTradeGroupMismatch_rejectsBeforeLegacyCleanup() {
        transactionTemplate.executeWithoutResult(status ->
                service.provisionShadow("DEMO001", null, "stock-admin"));
        jdbcTemplate.update(
                """
                update stock_account
                   set self_trade_group_id = 'BROKEN-GROUP'
                 where participant_category = 'LIQUIDITY_PROVIDER'
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.activateLive("DEMO001", null, "stock-admin")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("self-trade group is inconsistent");

        verify(accountOrderCleanupService, never())
                .cancelOpenOrderBookOrders(
                        org.mockito.ArgumentMatchers.any(StockAccount.class),
                        org.mockito.ArgumentMatchers.anyString()
                );
        assertThat(jdbcTemplate.queryForObject(
                "select enabled from stock_listing_auto_account_config where symbol = 'DEMO001'",
                Boolean.class
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select stage from stock_liquidity_transition where symbol = 'DEMO001'",
                String.class
        )).isEqualTo("SHADOW_READY");
    }

    @Test
    void provisionShadow_runningClock_rejectsBeforeAssetMutation() {
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(true, PRE_OPEN));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.provisionShadow("DEMO001", null, "stock-admin")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Pause the simulation clock");

        verify(freezeGuard, never())
                .acquireMutationPermit("liquidity-provider shadow provisioning");
        assertThat(jdbcTemplate.queryForObject(
                "select quantity from stock_holding where account_id = 100 and symbol = 'DEMO001'",
                Long.class
        )).isEqualTo(1_000_000L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_liquidity_transition",
                Integer.class
        )).isZero();
    }

    @Test
    void provisionShadow_insufficientUnreservedSourceInventory_rollsBack() {
        jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = 996000
                 where account_id = 100
                   and symbol = 'DEMO001'
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.provisionShadow(
                        "DEMO001",
                        new LiquidityProviderProvisionRequest(
                                null,
                                null,
                                null,
                                null,
                                "insufficient test"
                        ),
                        "stock-admin"
                )
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("enough unreserved inventory");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_account where participant_category = 'LIQUIDITY_PROVIDER'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_liquidity_transition",
                Integer.class
        )).isZero();
    }

    private void seedLegacySymbol() {
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price,
                    issued_shares, tradable_shares, tick_size,
                    price_limit_rate, enabled, created_at, updated_at
                ) values (
                    'DEMO001', '테스트 종목', 'ORDER_BOOK', 100,
                    1000000, 1000000, 1, 30, true, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(
                    symbol, enabled, market_status, updated_at
                ) values ('DEMO001', true, 'CLOSED', ?)
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_price(
                    symbol, current_price, previous_close, price_time, provider
                ) values ('DEMO001', 100, 100, ?, 'test')
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    cash_balance, created_at, updated_at
                ) values (
                    100, 'stock-listing-demo001', 'LEGACY-DEMO001',
                    'ACTIVE', 'LISTING_UNDERWRITER', 0, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (100, 'DEMO001', 1000000, 0, 100, ?)
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_listing_auto_account_config(
                    symbol, user_key, display_name, enabled,
                    position_side, operation_mode, strategy_profile,
                    initial_inventory_quantity, initial_issue_price,
                    max_order_quantity, order_ttl_seconds, price_offset_ticks,
                    target_spread_ticks, inventory_skew_ticks,
                    minimum_profit_rate, aggressive_unwind_threshold,
                    aggressive_order_ratio, target_buy_quantity,
                    target_sell_quantity, target_holding_quantity,
                    inventory_band_quantity, created_at, updated_at
                ) values (
                    'DEMO001', 'stock-listing-demo001', '기존 공급계정', true,
                    'SELL_ONLY', 'UNDERWRITER_RETURN', 'RETURN_FIRST',
                    1000000, 100, 1000, 60, 3, 8, 3,
                    1, 1, 0, 0, 1000, 1000000, 0, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
    }

    private void convertFixtureToPendingRoleSeparatedListing() {
        jdbcTemplate.update(
                """
                delete from stock_listing_auto_account_config
                 where symbol = 'DEMO001'
                """
        );
        jdbcTemplate.update(
                """
                update stock_order_book_market_config
                   set enabled = false,
                       market_status = 'CLOSED'
                 where symbol = 'DEMO001'
                """
        );
        jdbcTemplate.update(
                """
                update stock_account
                   set participant_category = 'ISSUE_UNDERWRITER',
                       self_trade_group_id = 'ISSUE_UNDERWRITER:DEFAULT'
                 where id = 100
                """
        );
        Long participantId = jdbcTemplate.queryForObject(
                """
                select id
                  from stock_market_participant
                 where participant_code = 'DEFAULT_ISSUE_UNDERWRITER'
                """,
                Long.class
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_contract(
                    contract_code, corporate_action_id, symbol,
                    participant_id, account_id, total_issue_quantity,
                    tradable_allocation_quantity, locked_allocation_quantity,
                    external_allocation_quantity, underwritten_quantity,
                    issue_price, underwriting_type, status, policy_version,
                    created_at, updated_at
                ) values (
                    'UW-TEST-DEMO001', null, 'DEMO001',
                    ?, 100, 1000000,
                    1000000, 0,
                    0, 1000000,
                    100, 'FIRM_COMMITMENT', 'ALLOCATED', 1,
                    ?, ?
                )
                """,
                participantId,
                PRE_OPEN,
                PRE_OPEN
        );
    }

    private SimulationClockSnapshot clockSnapshot(
            boolean running,
            LocalDateTime simulationDateTime
    ) {
        return new SimulationClockSnapshot(
                BUSINESS_DATE,
                simulationDateTime,
                BUSINESS_DATE.atStartOfDay(),
                simulationDateTime,
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
