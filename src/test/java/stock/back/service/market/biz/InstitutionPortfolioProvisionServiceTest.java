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
import org.springframework.transaction.support.TransactionTemplate;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.InstitutionPortfolioCreateRequest;
import stock.back.service.market.vo.InstitutionPortfolioResponse;
import stock.back.service.market.vo.InstitutionSuspensionRequest;
import stock.back.service.market.vo.InstitutionSymbolMandateResponse;
import web.common.core.simulation.SimulationClockSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstitutionPortfolioProvisionServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = BUSINESS_DATE.atTime(5, 0);

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private InstitutionPortfolioProvisionService provisionService;
    private InstitutionPortfolioRecommendationService recommendationService;
    private InstitutionEmergencyStopService emergencyStopService;
    private InstitutionPortfolioQueryService queryService;
    private MarketLedgerFreezeGuard freezeGuard;
    private SimulationClockService simulationClockService;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:institution_preset_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new FileSystemResource(batchH2Ddl())).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);

        simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentMarketDateTime()).thenReturn(NOW);
        when(simulationClockService.currentSnapshot()).thenReturn(clockSnapshot(true));
        SimulationMarketSessionService marketSessionService =
                new SimulationMarketSessionService(simulationClockService, "06:00", "18:00");
        freezeGuard = mock(MarketLedgerFreezeGuard.class);
        when(freezeGuard.acquireJdbcPreOpenMutationPermit("institution portfolio creation"))
                .thenReturn(BUSINESS_DATE);
        when(freezeGuard.acquireJdbcMutationPermit(
                "institution portfolio emergency suspension"
        )).thenReturn(BUSINESS_DATE);

        provisionService = new InstitutionPortfolioProvisionService(
                jdbcTemplate,
                new ObjectMapper(),
                simulationClockService,
                marketSessionService,
                freezeGuard
        );
        emergencyStopService = new InstitutionEmergencyStopService(
                jdbcTemplate,
                new ObjectMapper(),
                simulationClockService,
                freezeGuard,
                new MarketRoleOrderCleanupService(jdbcTemplate),
                transactionManager
        );
        queryService = new InstitutionPortfolioQueryService(
                JdbcClient.create(dataSource),
                simulationClockService
        );
        recommendationService = new InstitutionPortfolioRecommendationService(
                JdbcClient.create(dataSource)
        );
        seedPreOpenSymbols();
    }

    @Test
    void createPortfolio_runningPreOpen_createsOneLivePortfolioForCurrentOpening() {
        createDefaultPortfolio();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_market_participant "
                        + "where participant_type = 'INSTITUTIONAL_INVESTOR'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_account "
                        + "where participant_category = 'INSTITUTIONAL_INVESTOR'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_market_participant_account "
                        + "where account_role = 'INSTITUTIONAL_INVESTOR'",
                Integer.class
        )).isEqualTo(1);
        assertThat(count("stock_institution_portfolio")).isEqualTo(1);
        assertThat(count("stock_institution_symbol_mandate")).isEqualTo(3);
        assertThat(count("stock_account_cash_flow")).isEqualTo(1);
        assertThat(count("stock_market_policy_version")).isEqualTo(1);
        assertThat(count("stock_order")).isZero();

        assertThat(jdbcTemplate.queryForList(
                "select cash_balance from stock_account "
                        + "where participant_category = 'INSTITUTIONAL_INVESTOR' order by id",
                BigDecimal.class
        )).allSatisfy(value -> assertThat(value).isEqualByComparingTo("6000000.00"));
        assertThat(jdbcTemplate.queryForList(
                "select distinct reference_daily_volume from stock_institution_symbol_mandate",
                Long.class
        )).containsExactly(30_000L);
        assertInstitutionActivationScheduledFor(BUSINESS_DATE);
    }

    @Test
    void createPortfolio_twoIndependentCalls_createOnlyRequestedPortfolios() {
        createDefaultPortfolio();
        createPortfolio("INST_VALUE", "VALUE_CONTRARIAN");

        assertThat(count("stock_institution_portfolio")).isEqualTo(2);
        assertThat(count("stock_account_cash_flow")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select sum(cash_balance) from stock_account",
                BigDecimal.class
        )).isEqualByComparingTo("12000000.00");
        verify(freezeGuard, times(2))
                .acquireJdbcPreOpenMutationPermit("institution portfolio creation");
    }

    @Test
    void createPortfolio_selectedSymbol_sizesAumFromSelectedMarketCapitalization() {
        transactionTemplate.executeWithoutResult(status ->
                provisionService.createPortfolio(
                        new InstitutionPortfolioCreateRequest(
                                "INST_ONE",
                                "단일 종목 기관",
                                "BALANCED_LONG_TERM",
                                new BigDecimal("0.010000"),
                                List.of("DEMO001"),
                                "single symbol mandate"
                        ),
                        "stock-admin"
                )
        );

        assertThat(jdbcTemplate.queryForObject(
                """
                select account.cash_balance
                  from stock_institution_portfolio portfolio
                  join stock_account account on account.id = portfolio.account_id
                 where portfolio.portfolio_code = 'INST_ONE'
                """,
                BigDecimal.class
        )).isEqualByComparingTo("1000000.00");
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_institution_symbol_mandate mandate
                  join stock_institution_portfolio portfolio
                    on portfolio.id = mandate.portfolio_id
                 where portfolio.portfolio_code = 'INST_ONE'
                """,
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void createPortfolio_omittedAum_usesStyleRecommendationForSelectedSymbols() {
        transactionTemplate.executeWithoutResult(status ->
                provisionService.createPortfolio(
                        new InstitutionPortfolioCreateRequest(
                                "INST_ACTIVE",
                                "단기 적극 기관",
                                "ACTIVE_SHORT_TERM",
                                null,
                                List.of("DEMO003"),
                                "style default"
                        ),
                        "stock-admin"
                )
        );

        assertThat(jdbcTemplate.queryForObject(
                """
                select account.cash_balance
                  from stock_institution_portfolio portfolio
                  join stock_account account on account.id = portfolio.account_id
                 where portfolio.portfolio_code = 'INST_ACTIVE'
                """,
                BigDecimal.class
        )).isEqualByComparingTo("750000.00");
    }

    @Test
    void getRecommendation_threeSymbols_reportsStyleSpecificAumAndSelectableSymbols() {
        var recommendation = recommendationService.getRecommendation();

        assertThat(List.of(
                recommendation.activeSymbolCount(),
                recommendation.recommendedPortfolioCount(),
                recommendation.recommendedRemainingCount(),
                recommendation.recommendedAumAmountPerPortfolio()
        )).containsExactly(3, 3, 3L, new BigDecimal("6000000.00"));
        assertThat(recommendation.styles())
                .extracting(style -> List.of(
                        style.investmentStyle(),
                        style.recommended(),
                        style.recommendedAumRateOfMarketCap(),
                        style.recommendedAumAmountPerPortfolio()
                ))
                .containsExactly(
                        List.of(
                                "BALANCED_LONG_TERM",
                                true,
                                new BigDecimal("0.010000"),
                                new BigDecimal("6000000.00")
                        ),
                        List.of(
                                "VALUE_CONTRARIAN",
                                false,
                                new BigDecimal("0.005000"),
                                new BigDecimal("3000000.00")
                        ),
                        List.of(
                                "MOMENTUM",
                                false,
                                new BigDecimal("0.003500"),
                                new BigDecimal("2100000.00")
                        ),
                        List.of(
                                "ACTIVE_SHORT_TERM",
                                false,
                                new BigDecimal("0.002500"),
                                new BigDecimal("1500000.00")
                        )
                );
        assertThat(recommendation.symbols())
                .extracting(symbol -> List.of(
                        symbol.symbol(),
                        symbol.name(),
                        symbol.recommendedReferenceDailyVolume()
                ))
                .containsExactly(
                        List.of("DEMO001", "테스트 종목 1", 30_000L),
                        List.of("DEMO002", "테스트 종목 2", 30_000L),
                        List.of("DEMO003", "테스트 종목 3", 30_000L)
                );
    }

    @Test
    void getRecommendation_suspendedPortfolio_doesNotReduceActiveRecommendation() {
        createDefaultPortfolio();
        jdbcTemplate.update(
                "update stock_institution_portfolio set status = 'SUSPENDED'"
        );

        var recommendation = recommendationService.getRecommendation();

        assertThat(List.of(
                recommendation.currentPortfolioCount(),
                recommendation.recommendedRemainingCount()
        )).containsExactly(0L, 3L);
    }

    @Test
    void createPortfolio_runningRegularSession_schedulesNextOpeningAcrossAllEffectiveDates() {
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(BUSINESS_DATE.atTime(10, 0), true));

        createDefaultPortfolio();

        assertInstitutionActivationScheduledFor(BUSINESS_DATE.plusDays(1));
    }

    @Test
    void createPortfolio_atOpeningBoundary_schedulesFollowingOpening() {
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(BUSINESS_DATE.atTime(6, 0), true));

        createDefaultPortfolio();

        assertInstitutionActivationScheduledFor(BUSINESS_DATE.plusDays(1));
    }

    @Test
    void createPortfolio_runningAfterClose_schedulesNextOpeningAcrossAllEffectiveDates() {
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(BUSINESS_DATE.atTime(21, 0), true));

        createDefaultPortfolio();

        assertInstitutionActivationScheduledFor(BUSINESS_DATE.plusDays(1));
    }

    @Test
    void createPortfolio_businessDateLagBeyondOneDay_rejectsBeforeOpeningCapitalMutation() {
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(BUSINESS_DATE.plusDays(2).atTime(10, 0), true));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                provisionService.createPortfolio(defaultCreateRequest(), "stock-admin")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("inconsistent with the market business state");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_account "
                        + "where participant_category = 'INSTITUTIONAL_INVESTOR'",
                Integer.class
        )).isZero();
    }

    @Test
    void createPortfolio_eodFreezeInProgress_rejectsBeforeOpeningCapitalMutation() {
        when(freezeGuard.acquireJdbcPreOpenMutationPermit(
                "institution portfolio creation"
        )).thenThrow(StockException.conflict("ledger freeze is in progress"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                provisionService.createPortfolio(defaultCreateRequest(), "stock-admin")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("ledger freeze is in progress");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_account "
                        + "where participant_category = 'INSTITUTIONAL_INVESTOR'",
                Integer.class
        )).isZero();
    }

    @Test
    void getPortfolios_afterProvision_exposesAccountMandatesAndNoLiveOriginOrders() {
        createDefaultPortfolio();

        List<InstitutionPortfolioResponse> portfolios =
                transactionTemplate.execute(status -> queryService.getPortfolios());

        assertThat(portfolios).hasSize(1);
        assertThat(portfolios).allSatisfy(portfolio -> {
            assertThat(portfolio.executionMode()).isEqualTo("LIVE");
            assertThat(portfolio.accountStatus()).isEqualTo("ACTIVE");
            assertThat(portfolio.accountSelfTradeGroupId())
                    .isEqualTo(portfolio.participantSelfTradeGroupId());
            assertThat(portfolio.cashBalance()).isEqualByComparingTo("6000000.00");
            assertThat(portfolio.totalAsset()).isEqualByComparingTo("6000000.00");
            assertThat(portfolio.currentStockAllocationRate()).isEqualByComparingTo("0.000000");
            assertThat(portfolio.institutionalOpenOrderCount()).isZero();
            assertThat(portfolio.completedDecisionTradingDays()).isZero();
            assertThat(portfolio.recentDecisionFailureCount()).isZero();
            assertThat(portfolio.mandates()).hasSize(3);
            assertThat(portfolio.mandates()).allSatisfy(mandate -> {
                assertThat(mandate.referenceDailyVolume()).isEqualTo(30_000L);
                assertThat(mandate.projectedQuantity()).isZero();
                assertThat(mandate.action()).isNull();
            });
        });
    }

    @Test
    void getPortfolios_dailyPlans_withoutOpenOrders_areNotCountedAsProjectedHoldings() {
        createDefaultPortfolio();
        jdbcTemplate.update(
                """
                insert into stock_institution_daily_budget(
                    simulation_trade_date, portfolio_id, symbol,
                    reference_daily_volume, gross_quantity_limit, gross_notional_limit,
                    planned_buy_quantity, planned_sell_quantity,
                    planned_buy_amount, planned_sell_amount,
                    submitted_buy_amount, submitted_sell_amount,
                    executed_buy_amount, executed_sell_amount,
                    policy_version, version, created_at, updated_at
                )
                select ?, id, 'DEMO001',
                       30000, 1000, 100000.00,
                       100, 20, 10000.00, 2000.00,
                       0, 0, 0, 0, 1, 0, ?, ?
                  from stock_institution_portfolio
                 where portfolio_code = 'INST_PENSION'
                """,
                BUSINESS_DATE,
                NOW,
                NOW
        );

        InstitutionPortfolioResponse pension = transactionTemplate.execute(
                status -> queryService.getPortfolios().stream()
                        .filter(portfolio -> "INST_PENSION".equals(portfolio.portfolioCode()))
                        .findFirst()
                        .orElseThrow()
        );
        InstitutionSymbolMandateResponse demo001 = pension.mandates().stream()
                .filter(mandate -> "DEMO001".equals(mandate.symbol()))
                .findFirst()
                .orElseThrow();

        assertThat(demo001.projectedQuantity()).isZero();
    }

    @Test
    void suspendLive_runningClock_suspendsRejectsIntentAndCancelsOrdersAtomically() {
        createDefaultPortfolio();
        long portfolioId = portfolioId("INST_PENSION");
        LocalDateTime emergencyStopNow = BUSINESS_DATE.atTime(6, 30);
        long decisionRunId = insertPendingLiveIntent(portfolioId, emergencyStopNow);
        Long accountId = jdbcTemplate.queryForObject(
                "select account_id from stock_institution_portfolio where id = ?",
                Long.class,
                portfolioId
        );
        assertThat(accountId).isNotNull();
        insertInstitutionOpenBuyOrder(
                accountId,
                portfolioId,
                decisionRunId,
                emergencyStopNow
        );
        when(simulationClockService.currentMarketDateTime()).thenReturn(emergencyStopNow);
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(emergencyStopNow, true));

        emergencyStopService.suspend(
                portfolioId,
                new InstitutionSuspensionRequest("운영자 즉시 중단 검증"),
                "stock-admin"
        );
        emergencyStopService.suspend(
                portfolioId,
                new InstitutionSuspensionRequest("멱등 재시도"),
                "stock-admin"
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select execution_mode, status, policy_version, next_decision_at
                  from stock_institution_portfolio
                 where id = ?
                """,
                portfolioId
        )).containsEntry("execution_mode", "LIVE")
                .containsEntry("status", "SUSPENDED")
                .containsEntry("policy_version", 2L)
                .containsEntry("next_decision_at", null);
        assertThat(jdbcTemplate.queryForMap(
                """
                select status, submission_reason
                  from stock_institution_order_intent
                 where decision_run_id = ?
                   and symbol = 'DEMO002'
                """,
                decisionRunId
        )).containsEntry("status", "REJECTED")
                .containsEntry(
                        "submission_reason",
                        "ADMIN_EMERGENCY_SUSPEND:운영자 즉시 중단 검증"
                );
        assertThat(jdbcTemplate.queryForMap(
                """
                select version_no, status, change_reason
                  from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'INST_PENSION'
                   and version_no = 2
                """
        )).containsEntry("version_no", 2L)
                .containsEntry("status", "ACTIVE")
                .containsEntry("change_reason", "운영자 즉시 중단 검증");
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'INST_PENSION'
                   and version_no = 2
                """,
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "select status, reserved_cash from stock_order where id = 8801"
        )).containsEntry("status", "CANCELLED")
                .containsEntry("reserved_cash", BigDecimal.ZERO.setScale(2));
        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = ?",
                BigDecimal.class,
                accountId
        )).isEqualByComparingTo("6000000.00");
        verify(freezeGuard, times(2)).acquireJdbcMutationPermit(
                "institution portfolio emergency suspension"
        );
    }

    @Test
    void suspendLive_orderCleanupFails_rollsBackPortfolioIntentAndPolicyTogether() {
        createDefaultPortfolio();
        long portfolioId = portfolioId("INST_PENSION");
        LocalDateTime emergencyStopNow = BUSINESS_DATE.atTime(6, 30);
        long decisionRunId = insertPendingLiveIntent(portfolioId, emergencyStopNow);
        Long accountId = jdbcTemplate.queryForObject(
                "select account_id from stock_institution_portfolio where id = ?",
                Long.class,
                portfolioId
        );
        assertThat(accountId).isNotNull();
        MarketRoleOrderCleanupService failingCleanup =
                mock(MarketRoleOrderCleanupService.class);
        doThrow(new IllegalStateException("cleanup failed"))
                .when(failingCleanup)
                .cancelOpenOrderBookOrders(
                        accountId,
                        "INSTITUTIONAL_INVESTOR",
                        null,
                        emergencyStopNow
                );
        InstitutionEmergencyStopService failingEmergencyStopService =
                new InstitutionEmergencyStopService(
                        jdbcTemplate,
                        new ObjectMapper(),
                        simulationClockService,
                        freezeGuard,
                        failingCleanup,
                        transactionManager
                );
        when(simulationClockService.currentMarketDateTime()).thenReturn(emergencyStopNow);
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(emergencyStopNow, true));

        assertThatThrownBy(() -> failingEmergencyStopService.suspend(
                portfolioId,
                new InstitutionSuspensionRequest("rollback verification"),
                "stock-admin"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cleanup failed");

        assertThat(jdbcTemplate.queryForMap(
                """
                select execution_mode, status, policy_version
                  from stock_institution_portfolio
                 where id = ?
                """,
                portfolioId
        )).containsEntry("execution_mode", "LIVE")
                .containsEntry("status", "ACTIVE")
                .containsEntry("policy_version", 1L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select status, submission_reason
                  from stock_institution_order_intent
                 where decision_run_id = ?
                   and symbol = 'DEMO002'
                """,
                decisionRunId
        )).containsEntry("status", "PENDING")
                .containsEntry("submission_reason", null);
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'INST_PENSION'
                   and version_no = 2
                """,
                Integer.class
        )).isZero();
    }

    @Test
    void suspendLive_eodFreezeInProgress_leavesPortfolioAndIntentUnchanged() {
        createDefaultPortfolio();
        long portfolioId = portfolioId("INST_PENSION");
        LocalDateTime emergencyStopNow = BUSINESS_DATE.atTime(6, 30);
        long decisionRunId = insertPendingLiveIntent(portfolioId, emergencyStopNow);
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(emergencyStopNow, true));
        when(freezeGuard.acquireJdbcMutationPermit(
                "institution portfolio emergency suspension"
        )).thenThrow(StockException.conflict("ledger freeze is in progress"));

        assertThatThrownBy(() -> emergencyStopService.suspend(
                portfolioId,
                new InstitutionSuspensionRequest("동결 중 중단 요청"),
                "stock-admin"
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("ledger freeze is in progress");

        assertThat(jdbcTemplate.queryForMap(
                """
                select status, policy_version
                  from stock_institution_portfolio
                 where id = ?
                """,
                portfolioId
        )).containsEntry("status", "ACTIVE")
                .containsEntry("policy_version", 1L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select status, submission_reason
                  from stock_institution_order_intent
                 where decision_run_id = ?
                   and symbol = 'DEMO002'
                """,
                decisionRunId
        )).containsEntry("status", "PENDING")
                .containsEntry("submission_reason", null);
    }

    private void createDefaultPortfolio() {
        transactionTemplate.executeWithoutResult(status ->
                provisionService.createPortfolio(defaultCreateRequest(), "stock-admin")
        );
    }

    private void createPortfolio(String portfolioCode, String investmentStyle) {
        transactionTemplate.executeWithoutResult(status ->
                provisionService.createPortfolio(
                        new InstitutionPortfolioCreateRequest(
                                portfolioCode,
                                portfolioCode + " 테스트",
                                investmentStyle,
                                new BigDecimal("0.010000"),
                                List.of("DEMO001", "DEMO002", "DEMO003"),
                                "independent institution test"
                        ),
                        "stock-admin"
                )
        );
    }

    private void assertInstitutionActivationScheduledFor(LocalDate businessDate) {
        LocalDateTime nextMarketOpen = businessDate.atTime(6, 0);
        assertThat(List.of(
                jdbcTemplate.queryForObject(
                        "select next_decision_at from stock_institution_portfolio "
                                + "where portfolio_code = 'INST_PENSION'",
                        LocalDateTime.class
                ),
                jdbcTemplate.queryForObject(
                        """
                        select mapping.effective_from
                          from stock_market_participant_account mapping
                          join stock_institution_portfolio portfolio
                            on portfolio.participant_id = mapping.participant_id
                           and portfolio.account_id = mapping.account_id
                         where portfolio.portfolio_code = 'INST_PENSION'
                        """,
                        LocalDate.class
                ),
                jdbcTemplate.queryForObject(
                        """
                        select cash_flow.effective_business_date
                          from stock_account_cash_flow cash_flow
                          join stock_institution_portfolio portfolio
                            on portfolio.account_id = cash_flow.account_id
                         where portfolio.portfolio_code = 'INST_PENSION'
                           and cash_flow.reason = 'OPENING_GRANT'
                        """,
                        LocalDate.class
                ),
                jdbcTemplate.queryForObject(
                        """
                        select effective_business_date
                          from stock_market_policy_version
                         where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                           and scope_key = 'INST_PENSION'
                           and version_no = 1
                        """,
                        LocalDate.class
                ),
                jdbcTemplate.queryForObject(
                        "select count(*) from stock_order",
                        Integer.class
                )
        )).containsExactly(
                nextMarketOpen,
                businessDate,
                businessDate,
                businessDate,
                0
        );
    }

    private InstitutionPortfolioCreateRequest defaultCreateRequest() {
        return new InstitutionPortfolioCreateRequest(
                "INST_PENSION",
                "축소 연기금 균형형",
                "BALANCED_LONG_TERM",
                new BigDecimal("0.010000"),
                List.of("DEMO001", "DEMO002", "DEMO003"),
                "independent institution baseline"
        );
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table,
                Integer.class
        );
    }

    private long portfolioId(String portfolioCode) {
        Long portfolioId = jdbcTemplate.queryForObject(
                "select id from stock_institution_portfolio where portfolio_code = ?",
                Long.class,
                portfolioCode
        );
        assertThat(portfolioId).isNotNull();
        return portfolioId;
    }

    private long insertPendingLiveIntent(long portfolioId, LocalDateTime decisionSlot) {
        jdbcTemplate.update(
                """
                insert into stock_institution_decision_run(
                    decision_slot, simulation_trade_date, portfolio_id,
                    execution_mode, policy_version, deterministic_seed,
                    status, error_message, created_at, completed_at
                ) values (?, ?, ?, 'LIVE', 1, 99, 'COMPLETED', null, ?, ?)
                """,
                decisionSlot,
                decisionSlot.toLocalDate(),
                portfolioId,
                decisionSlot,
                decisionSlot
        );
        Long decisionRunId = jdbcTemplate.queryForObject(
                """
                select id
                  from stock_institution_decision_run
                 where portfolio_id = ?
                   and decision_slot = ?
                """,
                Long.class,
                portfolioId,
                decisionSlot
        );
        assertThat(decisionRunId).isNotNull();
        jdbcTemplate.update(
                """
                insert into stock_institution_order_intent(
                    decision_run_id, symbol, portfolio_id, participant_id, account_id,
                    side, requested_quantity, planned_amount, reference_daily_volume,
                    execution_aggression_pressure, policy_version, status, attempt_count,
                    submitted_order_id, submitted_price, submitted_quantity,
                    submission_reason, created_at, updated_at, submitted_at
                )
                select ?, 'DEMO002', id, participant_id, account_id,
                       'BUY', 10, 1000.00, 30000,
                       0.000000, 1, 'PENDING', 0,
                       null, null, 0, null, ?, ?, null
                  from stock_institution_portfolio
                 where id = ?
                """,
                decisionRunId,
                decisionSlot,
                decisionSlot,
                portfolioId
        );
        return decisionRunId;
    }

    private void insertInstitutionOpenBuyOrder(
            long accountId,
            long portfolioId,
            long decisionRunId,
            LocalDateTime createdAt
    ) {
        Long participantId = jdbcTemplate.queryForObject(
                "select participant_id from stock_institution_portfolio where id = ?",
                Long.class,
                portfolioId
        );
        assertThat(participantId).isNotNull();
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance - 1000 where id = ?",
                accountId
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, client_order_id, account_id, origin_type,
                    self_trade_group_id, symbol, market_type, side,
                    order_type, status, limit_price, quantity,
                    filled_quantity, reserved_cash, expires_at,
                    created_at, updated_at
                ) values (
                    8801, 'institution-open-buy', ?, 'INSTITUTIONAL_INVESTOR',
                    'INSTITUTIONAL_INVESTOR:INST_PENSION', 'DEMO001',
                    'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING',
                    100, 10, 0, 1000, ?, ?, ?
                )
                """,
                accountId,
                createdAt.plusHours(1),
                createdAt,
                createdAt
        );
        jdbcTemplate.update(
                """
                insert into stock_order_strategy_origin(
                    order_id, origin_type, participant_id, portfolio_id,
                    decision_run_id, policy_version, created_at
                ) values (
                    8801, 'INSTITUTIONAL_INVESTOR', ?, ?, ?, 1, ?
                )
                """,
                participantId,
                portfolioId,
                decisionRunId,
                createdAt
        );
    }

    private void seedPreOpenSymbols() {
        for (int index = 1; index <= 3; index++) {
            String symbol = "DEMO00" + index;
            BigDecimal price = BigDecimal.valueOf(index * 100L);
            jdbcTemplate.update(
                    """
                    insert into stock_order_book_instrument(
                        symbol, name, market, initial_price,
                        issued_shares, tradable_shares, tick_size,
                        price_limit_rate, enabled, created_at, updated_at
                    ) values (?, ?, 'ORDER_BOOK', ?, 2000000, 1000000, 1, 30, true, ?, ?)
                    """,
                    symbol,
                    "테스트 종목 " + index,
                    price,
                    NOW,
                    NOW
            );
            jdbcTemplate.update(
                    """
                    insert into stock_order_book_market_config(
                        symbol, enabled, market_status, updated_at
                    ) values (?, true, 'CLOSED', ?)
                    """,
                    symbol,
                    NOW
            );
            jdbcTemplate.update(
                    """
                    insert into stock_price(
                        symbol, current_price, previous_close, price_time, provider
                    ) values (?, ?, ?, ?, 'test')
                    """,
                    symbol,
                    price,
                    price,
                    NOW
            );
        }
    }

    private SimulationClockSnapshot clockSnapshot(boolean running) {
        return clockSnapshot(NOW, running);
    }

    private SimulationClockSnapshot clockSnapshot(
            LocalDateTime simulationDateTime,
            boolean running
    ) {
        LocalDate simulationDate = simulationDateTime.toLocalDate();
        return new SimulationClockSnapshot(
                simulationDate,
                simulationDateTime,
                simulationDate.atStartOfDay(),
                simulationDateTime,
                simulationDate.atStartOfDay(),
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
