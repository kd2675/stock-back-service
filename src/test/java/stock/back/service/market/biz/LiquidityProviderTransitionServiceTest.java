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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.LiquidityProviderProvisionRequest;
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
        when(freezeGuard.acquireJdbcPreOpenMutationPermit("liquidity-provider live provisioning"))
                .thenReturn(BUSINESS_DATE);
        service = new LiquidityProviderTransitionService(
                jdbcTemplate,
                new ObjectMapper(),
                simulationClockService,
                marketSessionService,
                freezeGuard,
                new LiquidityProviderPolicyPresetCatalog()
        );
        recommendationService = new LiquidityProviderRecommendationService(
                JdbcClient.create(dataSource)
        );
        seedProvisionableSymbol();
    }

    @Test
    void provisionLive_defaultRates_createsDedicatedProviderFromIssueInventory() {
        transactionTemplate.executeWithoutResult(status ->
                service.provisionLive("demo001", null, "stock-admin"));

        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = 100",
                BigDecimal.class
        )).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForMap(
                """
                select mandate.execution_mode, mandate.reference_daily_volume,
                       mandate.max_order_quantity, mandate.target_inventory_quantity,
                       mandate.inventory_band_quantity,
                       mandate.target_open_participation_rate,
                       mandate.daily_execution_participation_rate,
                       mandate.daily_submission_multiplier,
                       mandate.minimum_quote_lifetime_seconds,
                       mandate.order_ttl_seconds,
                       mandate.quote_interval_seconds,
                       transition.stage,
                       transition.seed_inventory_quantity, transition.seed_cash_amount,
                       transition.activated_at
                  from stock_liquidity_mandate mandate
                  join stock_liquidity_transition transition
                    on transition.mandate_id = mandate.id
                 where mandate.symbol = 'DEMO001'
                """
        )).containsEntry("execution_mode", "LIVE")
                .containsEntry("reference_daily_volume", 30_000L)
                .containsEntry("max_order_quantity", 225L)
                .containsEntry("target_inventory_quantity", 5_000L)
                .containsEntry("inventory_band_quantity", 5_000L)
                .containsEntry(
                        "target_open_participation_rate",
                        new BigDecimal("0.007500")
                )
                .containsEntry(
                        "daily_execution_participation_rate",
                        new BigDecimal("0.180000")
                )
                .containsEntry("daily_submission_multiplier", new BigDecimal("5.0000"))
                .containsEntry("minimum_quote_lifetime_seconds", 600)
                .containsEntry("order_ttl_seconds", 1_800)
                .containsEntry("quote_interval_seconds", 300)
                .containsEntry("stage", "LIVE_ACTIVE")
                .containsEntry("seed_inventory_quantity", 5_000L)
                .containsEntry("seed_cash_amount", new BigDecimal("500000.00"))
                .doesNotContainValue(null);
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
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                   and version_no = 1
                   and status = 'ACTIVE'
                """,
                Integer.class
        )).isOne();
    }

    @Test
    void provisionLive_pendingRoleSeparatedListing_enablesMarketForNextSession() {
        convertFixtureToPendingRoleSeparatedListing();
        transactionTemplate.executeWithoutResult(status ->
                service.provisionLive(
                        "DEMO001",
                        new LiquidityProviderProvisionRequest(
                                null,
                                null,
                                null,
                                null,
                                "new listing LP ready"
                        ),
                        "stock-admin"
                ));

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
    void provisionLive_multipleContractsForSameUnderwriter_resolvesOneSourceAccount() {
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
                service.provisionLive("DEMO001", null, "stock-admin"));

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
    void recommendation_inactiveIssueAccount_doesNotOfferUnusableSource() {
        jdbcTemplate.update(
                "update stock_account set status = 'CLOSED' where id = 100"
        );

        var recommendation = recommendationService.getRecommendation();

        assertThat(recommendation.symbols()).singleElement().satisfies(symbol -> {
            assertThat(symbol.recommendedSourceAccountId()).isNull();
            assertThat(symbol.sourceAvailableQuantity()).isZero();
            assertThat(symbol.creationEligible()).isFalse();
            assertThat(symbol.eligibilityReason())
                    .isEqualTo("SOURCE_ACCOUNT_REQUIRED");
        });
    }

    @Test
    void recommendation_wrongSourceRole_doesNotOfferUnusableSource() {
        jdbcTemplate.update(
                """
                update stock_account
                   set participant_category = 'MANUAL_PARTICIPANT'
                 where id = 100
                """
        );

        var recommendation = recommendationService.getRecommendation();

        assertThat(recommendation.symbols()).singleElement().satisfies(symbol -> {
            assertThat(symbol.recommendedSourceAccountId()).isNull();
            assertThat(symbol.creationEligible()).isFalse();
            assertThat(symbol.eligibilityReason())
                    .isEqualTo("SOURCE_ACCOUNT_REQUIRED");
        });
    }

    @Test
    void provisionLive_participantSelfTradeGroupMismatch_rollsBackProvisioning() {
        jdbcTemplate.update(
                """
                update stock_market_participant
                   set self_trade_group_id = 'BROKEN-GROUP'
                 where participant_code = 'DEFAULT_LIQUIDITY_PROVIDER'
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.provisionLive("DEMO001", null, "stock-admin")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Default liquidity-provider participant is missing or inconsistent");

        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = 100",
                BigDecimal.class
        )).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject(
                """
                select reserved_quantity
                  from stock_holding
                 where account_id = 100
                   and symbol = 'DEMO001'
                """,
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_liquidity_transition",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_account where participant_category = 'LIQUIDITY_PROVIDER'",
                Integer.class
        )).isZero();
    }

    @Test
    void provisionLive_runningClock_rejectsBeforeAssetMutation() {
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(true, PRE_OPEN));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.provisionLive("DEMO001", null, "stock-admin")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Pause the simulation clock");

        verify(freezeGuard, never())
                .acquireJdbcPreOpenMutationPermit("liquidity-provider live provisioning");
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
    void provisionLive_insufficientUnreservedSourceInventory_rollsBack() {
        jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = 996000
                 where account_id = 100
                   and symbol = 'DEMO001'
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.provisionLive(
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

    @Test
    void provisionLive_repeatedRequest_isIdempotent() {
        transactionTemplate.executeWithoutResult(status ->
                service.provisionLive("DEMO001", null, "stock-admin"));
        transactionTemplate.executeWithoutResult(status ->
                service.provisionLive("DEMO001", null, "stock-admin"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_liquidity_mandate where symbol = 'DEMO001'",
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_account where participant_category = 'LIQUIDITY_PROVIDER'",
                Integer.class
        )).isOne();
    }

    private void seedProvisionableSymbol() {
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
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    100, 'stock-issue-underwriter-demo001', 'UW-DEMO001',
                    'ACTIVE', 'ISSUE_UNDERWRITER',
                    'ISSUE_UNDERWRITER:DEFAULT', 0, ?, ?
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

    private void convertFixtureToPendingRoleSeparatedListing() {
        jdbcTemplate.update(
                """
                update stock_order_book_market_config
                   set enabled = false,
                       market_status = 'CLOSED'
                 where symbol = 'DEMO001'
                """
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
