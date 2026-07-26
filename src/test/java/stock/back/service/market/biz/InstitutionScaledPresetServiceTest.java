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
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.market.vo.InstitutionPilotActivationRequest;
import stock.back.service.market.vo.InstitutionPilotSuspensionRequest;
import stock.back.service.market.vo.InstitutionPortfolioResponse;
import stock.back.service.market.vo.InstitutionScaledPresetRequest;
import stock.back.service.market.vo.InstitutionSymbolMandateResponse;
import stock.back.service.trading.biz.AccountOrderCleanupService;
import web.common.core.simulation.SimulationClockSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstitutionScaledPresetServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = BUSINESS_DATE.atTime(5, 0);

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private InstitutionScaledPresetService presetService;
    private InstitutionPilotTransitionService pilotTransitionService;
    private InstitutionPilotEmergencyStopService emergencyStopService;
    private InstitutionPortfolioQueryService queryService;
    private MarketLedgerFreezeGuard freezeGuard;
    private SimulationClockService simulationClockService;
    private StockAccountRepository stockAccountRepository;
    private AccountOrderCleanupService accountOrderCleanupService;

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
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);

        simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentMarketDateTime()).thenReturn(NOW);
        when(simulationClockService.currentSnapshot()).thenReturn(clockSnapshot());
        SimulationMarketSessionService marketSessionService =
                new SimulationMarketSessionService(simulationClockService, "06:00", "18:00");
        freezeGuard = mock(MarketLedgerFreezeGuard.class);
        when(freezeGuard.acquireMutationPermit("scaled institution preset creation"))
                .thenReturn(BUSINESS_DATE);

        presetService = new InstitutionScaledPresetService(
                jdbcTemplate,
                new ObjectMapper(),
                simulationClockService,
                marketSessionService,
                freezeGuard
        );
        pilotTransitionService = new InstitutionPilotTransitionService(
                jdbcTemplate,
                new ObjectMapper(),
                simulationClockService,
                marketSessionService,
                freezeGuard
        );
        stockAccountRepository = mock(StockAccountRepository.class);
        accountOrderCleanupService = mock(AccountOrderCleanupService.class);
        emergencyStopService = new InstitutionPilotEmergencyStopService(
                jdbcTemplate,
                new ObjectMapper(),
                simulationClockService,
                stockAccountRepository,
                accountOrderCleanupService,
                transactionManager
        );
        queryService = new InstitutionPortfolioQueryService(
                JdbcClient.create(dataSource),
                simulationClockService
        );
        seedPreOpenSymbols();
    }

    @Test
    void createScaledDefaults_threeSymbolMarket_createsFourShadowPortfoliosWithoutOrders() {
        transactionTemplate.executeWithoutResult(status -> presetService.createScaledDefaults(
                new InstitutionScaledPresetRequest(
                        new BigDecimal("0.010000"),
                        "scaled market baseline"
                ),
                "stock-admin"
        ));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_market_participant "
                        + "where participant_type = 'INSTITUTIONAL_INVESTOR'",
                Integer.class
        )).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_account "
                        + "where participant_category = 'INSTITUTIONAL_INVESTOR'",
                Integer.class
        )).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_market_participant_account "
                        + "where account_role = 'INSTITUTIONAL_INVESTOR'",
                Integer.class
        )).isEqualTo(4);
        assertThat(count("stock_institution_portfolio")).isEqualTo(4);
        assertThat(count("stock_institution_symbol_mandate")).isEqualTo(12);
        assertThat(count("stock_account_cash_flow")).isEqualTo(4);
        assertThat(count("stock_market_policy_version")).isEqualTo(4);
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
        assertThat(jdbcTemplate.queryForList(
                "select distinct next_decision_at from stock_institution_portfolio",
                LocalDateTime.class
        )).containsExactly(LocalDateTime.of(2027, 1, 28, 6, 0));
        assertThat(jdbcTemplate.queryForList(
                "select distinct effective_business_date from stock_market_policy_version",
                LocalDate.class
        )).containsExactly(LocalDate.of(2027, 1, 28));
    }

    @Test
    void createScaledDefaults_secondCall_isIdempotentAndDoesNotDuplicateOpeningCash() {
        transactionTemplate.executeWithoutResult(status ->
                presetService.createScaledDefaults(null, "stock-admin"));
        transactionTemplate.executeWithoutResult(status ->
                presetService.createScaledDefaults(null, "stock-admin"));

        assertThat(count("stock_institution_portfolio")).isEqualTo(4);
        assertThat(count("stock_account_cash_flow")).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "select sum(cash_balance) from stock_account",
                BigDecimal.class
        )).isEqualByComparingTo("24000000.00");
        verify(freezeGuard, times(1))
                .acquireMutationPermit("scaled institution preset creation");
    }

    @Test
    void createScaledDefaults_runningClock_rejectsBeforeOpeningCapitalMutation() {
        when(simulationClockService.currentSnapshot()).thenReturn(clockSnapshot(true));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                presetService.createScaledDefaults(null, "stock-admin")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Pause the simulation clock");

        verify(freezeGuard, never())
                .acquireMutationPermit("scaled institution preset creation");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_account "
                        + "where participant_category = 'INSTITUTIONAL_INVESTOR'",
                Integer.class
        )).isZero();
    }

    @Test
    void getPortfolios_afterProvision_exposesAccountMandatesAndNoLiveOriginOrders() {
        transactionTemplate.executeWithoutResult(status ->
                presetService.createScaledDefaults(null, "stock-admin"));

        List<InstitutionPortfolioResponse> portfolios =
                transactionTemplate.execute(status -> queryService.getPortfolios());

        assertThat(portfolios).hasSize(4);
        assertThat(portfolios).allSatisfy(portfolio -> {
            assertThat(portfolio.executionMode()).isEqualTo("SHADOW");
            assertThat(portfolio.accountStatus()).isEqualTo("ACTIVE");
            assertThat(portfolio.accountSelfTradeGroupId())
                    .isEqualTo(portfolio.participantSelfTradeGroupId());
            assertThat(portfolio.cashBalance()).isEqualByComparingTo("6000000.00");
            assertThat(portfolio.totalAsset()).isEqualByComparingTo("6000000.00");
            assertThat(portfolio.currentStockAllocationRate()).isEqualByComparingTo("0.000000");
            assertThat(portfolio.institutionalOpenOrderCount()).isZero();
            assertThat(portfolio.completedShadowTradingDays()).isZero();
            assertThat(portfolio.recentShadowFailureCount()).isZero();
            assertThat(portfolio.mandates()).hasSize(3);
            assertThat(portfolio.mandates()).allSatisfy(mandate -> {
                assertThat(mandate.referenceDailyVolume()).isEqualTo(30_000L);
                assertThat(mandate.projectedQuantity()).isZero();
                assertThat(mandate.action()).isNull();
            });
        });
    }

    @Test
    void getPortfolios_dailyShadowPlans_areIncludedInProjectedQuantity() {
        transactionTemplate.executeWithoutResult(status ->
                presetService.createScaledDefaults(null, "stock-admin"));
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

        assertThat(demo001.projectedQuantity()).isEqualTo(80L);
    }

    @Test
    void activatePilot_reviewedShadowPortfolio_selectsOneSymbolAndVersionsPolicy() {
        transactionTemplate.executeWithoutResult(status ->
                presetService.createScaledDefaults(null, "stock-admin"));
        long portfolioId = portfolioId("INST_PENSION");
        seedCompletedShadowDays(portfolioId, 20);
        LocalDate pilotDate = BUSINESS_DATE.plusDays(21);
        LocalDateTime pilotNow = pilotDate.atTime(5, 0);
        when(simulationClockService.currentMarketDateTime()).thenReturn(pilotNow);
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(pilotNow, false));
        when(freezeGuard.acquireMutationPermit("institution pilot activation"))
                .thenReturn(pilotDate);

        transactionTemplate.executeWithoutResult(status ->
                pilotTransitionService.activatePilot(
                        portfolioId,
                        new InstitutionPilotActivationRequest(
                                "demo002",
                                "20일 shadow 검토 완료"
                        ),
                        "stock-admin"
                )
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select execution_mode, status, policy_version, next_decision_at
                  from stock_institution_portfolio
                 where id = ?
                """,
                portfolioId
        )).containsEntry("execution_mode", "PILOT")
                .containsEntry("status", "ACTIVE")
                .containsEntry("policy_version", 2L)
                .containsEntry("next_decision_at", java.sql.Timestamp.valueOf(pilotDate.atTime(6, 0)));
        assertThat(jdbcTemplate.queryForList(
                """
                select symbol
                  from stock_institution_symbol_mandate
                 where portfolio_id = ?
                   and enabled = true
                 order by symbol
                """,
                String.class,
                portfolioId
        )).containsExactly("DEMO002");
        assertThat(jdbcTemplate.queryForMap(
                """
                select version_no, effective_business_date, status
                  from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'INST_PENSION'
                   and version_no = 2
                """
        )).containsEntry("version_no", 2L)
                .containsEntry("effective_business_date", java.sql.Date.valueOf(pilotDate))
                .containsEntry("status", "SCHEDULED");
    }

    @Test
    void activatePilot_insufficientShadowDays_rejectsWithoutModeChange() {
        transactionTemplate.executeWithoutResult(status ->
                presetService.createScaledDefaults(null, "stock-admin"));
        long portfolioId = portfolioId("INST_PENSION");
        seedCompletedShadowDays(portfolioId, 19);
        LocalDate pilotDate = BUSINESS_DATE.plusDays(21);
        LocalDateTime pilotNow = pilotDate.atTime(5, 0);
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(pilotNow, false));
        when(freezeGuard.acquireMutationPermit("institution pilot activation"))
                .thenReturn(pilotDate);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                pilotTransitionService.activatePilot(
                        portfolioId,
                        new InstitutionPilotActivationRequest("DEMO001", "too early"),
                        "stock-admin"
                )
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("at least 20 completed SHADOW trading days");

        assertThat(jdbcTemplate.queryForObject(
                "select execution_mode from stock_institution_portfolio where id = ?",
                String.class,
                portfolioId
        )).isEqualTo("SHADOW");
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_institution_symbol_mandate
                 where portfolio_id = ?
                   and enabled = true
                """,
                Integer.class,
                portfolioId
        )).isEqualTo(3);
    }

    @Test
    void suspendPilot_runningClock_suspendsRejectsIntentAndCancelsOrdersAtomically() {
        transactionTemplate.executeWithoutResult(status ->
                presetService.createScaledDefaults(null, "stock-admin"));
        long portfolioId = portfolioId("INST_PENSION");
        seedCompletedShadowDays(portfolioId, 20);
        LocalDate pilotDate = BUSINESS_DATE.plusDays(21);
        LocalDateTime activationNow = pilotDate.atTime(5, 0);
        when(simulationClockService.currentMarketDateTime()).thenReturn(activationNow);
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(activationNow, false));
        when(freezeGuard.acquireMutationPermit("institution pilot activation"))
                .thenReturn(pilotDate);
        transactionTemplate.executeWithoutResult(status ->
                pilotTransitionService.activatePilot(
                        portfolioId,
                        new InstitutionPilotActivationRequest("DEMO002", "pilot ready"),
                        "stock-admin"
                )
        );

        LocalDateTime emergencyStopNow = pilotDate.atTime(6, 30);
        long decisionRunId = insertPendingPilotIntent(portfolioId, emergencyStopNow);
        Long accountId = jdbcTemplate.queryForObject(
                "select account_id from stock_institution_portfolio where id = ?",
                Long.class,
                portfolioId
        );
        assertThat(accountId).isNotNull();
        StockAccount account = mock(StockAccount.class);
        when(account.getId()).thenReturn(accountId);
        when(stockAccountRepository.findAllByIdInForUpdate(List.of(accountId)))
                .thenReturn(List.of(account));
        when(simulationClockService.currentMarketDateTime()).thenReturn(emergencyStopNow);
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(emergencyStopNow, true));

        emergencyStopService.suspend(
                portfolioId,
                new InstitutionPilotSuspensionRequest("운영자 즉시 중단 검증"),
                "stock-admin"
        );
        emergencyStopService.suspend(
                portfolioId,
                new InstitutionPilotSuspensionRequest("멱등 재시도"),
                "stock-admin"
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select execution_mode, status, policy_version, next_decision_at
                  from stock_institution_portfolio
                 where id = ?
                """,
                portfolioId
        )).containsEntry("execution_mode", "PILOT")
                .containsEntry("status", "SUSPENDED")
                .containsEntry("policy_version", 3L)
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
                   and version_no = 3
                """
        )).containsEntry("version_no", 3L)
                .containsEntry("status", "ACTIVE")
                .containsEntry("change_reason", "운영자 즉시 중단 검증");
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'INST_PENSION'
                   and version_no = 3
                """,
                Integer.class
        )).isEqualTo(1);
        verify(accountOrderCleanupService, times(2))
                .cancelOpenOrderBookOrders(account);
    }

    @Test
    void suspendPilot_orderCleanupFails_rollsBackPortfolioIntentAndPolicyTogether() {
        transactionTemplate.executeWithoutResult(status ->
                presetService.createScaledDefaults(null, "stock-admin"));
        long portfolioId = portfolioId("INST_PENSION");
        seedCompletedShadowDays(portfolioId, 20);
        LocalDate pilotDate = BUSINESS_DATE.plusDays(21);
        LocalDateTime activationNow = pilotDate.atTime(5, 0);
        when(simulationClockService.currentMarketDateTime()).thenReturn(activationNow);
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(activationNow, false));
        when(freezeGuard.acquireMutationPermit("institution pilot activation"))
                .thenReturn(pilotDate);
        transactionTemplate.executeWithoutResult(status ->
                pilotTransitionService.activatePilot(
                        portfolioId,
                        new InstitutionPilotActivationRequest("DEMO002", "pilot ready"),
                        "stock-admin"
                )
        );

        LocalDateTime emergencyStopNow = pilotDate.atTime(6, 30);
        long decisionRunId = insertPendingPilotIntent(portfolioId, emergencyStopNow);
        Long accountId = jdbcTemplate.queryForObject(
                "select account_id from stock_institution_portfolio where id = ?",
                Long.class,
                portfolioId
        );
        assertThat(accountId).isNotNull();
        StockAccount account = mock(StockAccount.class);
        when(account.getId()).thenReturn(accountId);
        when(stockAccountRepository.findAllByIdInForUpdate(List.of(accountId)))
                .thenReturn(List.of(account));
        doThrow(new IllegalStateException("cleanup failed"))
                .when(accountOrderCleanupService)
                .cancelOpenOrderBookOrders(account);
        when(simulationClockService.currentMarketDateTime()).thenReturn(emergencyStopNow);
        when(simulationClockService.currentSnapshot())
                .thenReturn(clockSnapshot(emergencyStopNow, true));

        assertThatThrownBy(() -> emergencyStopService.suspend(
                portfolioId,
                new InstitutionPilotSuspensionRequest("rollback verification"),
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
        )).containsEntry("execution_mode", "PILOT")
                .containsEntry("status", "ACTIVE")
                .containsEntry("policy_version", 2L);
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
                   and version_no = 3
                """,
                Integer.class
        )).isZero();
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

    private void seedCompletedShadowDays(long portfolioId, int tradingDays) {
        for (int day = 1; day <= tradingDays; day++) {
            LocalDate simulationTradeDate = BUSINESS_DATE.plusDays(day);
            LocalDateTime decisionSlot = simulationTradeDate.atTime(6, 0);
            jdbcTemplate.update(
                    """
                    insert into stock_institution_decision_run(
                        decision_slot, simulation_trade_date, portfolio_id,
                        execution_mode, policy_version, deterministic_seed,
                        status, error_message, created_at, completed_at
                    ) values (?, ?, ?, 'SHADOW', 1, ?, 'COMPLETED', null, ?, ?)
                    """,
                    decisionSlot,
                    simulationTradeDate,
                    portfolioId,
                    (long) day,
                    decisionSlot,
                    decisionSlot
            );
        }
    }

    private long insertPendingPilotIntent(long portfolioId, LocalDateTime decisionSlot) {
        jdbcTemplate.update(
                """
                insert into stock_institution_decision_run(
                    decision_slot, simulation_trade_date, portfolio_id,
                    execution_mode, policy_version, deterministic_seed,
                    status, error_message, created_at, completed_at
                ) values (?, ?, ?, 'PILOT', 2, 99, 'COMPLETED', null, ?, ?)
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
                       0.000000, 2, 'PENDING', 0,
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

    private SimulationClockSnapshot clockSnapshot() {
        return clockSnapshot(false);
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
