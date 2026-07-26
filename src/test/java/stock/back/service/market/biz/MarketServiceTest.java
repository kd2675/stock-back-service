package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlement;
import stock.back.service.database.entity.StockCorporateActionEntitlementStatus;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.entity.StockInstrumentReportEvent;
import stock.back.service.database.entity.StockInstrumentReportEventType;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.entity.StockPriceTick;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantProfileConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockInstrumentReportEventRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockPriceTickRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.database.repository.StockVirtualMarketConfigRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.market.vo.AdminFundFlowScope;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantHoldingGroupResponse;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantProfileOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;
import stock.back.service.market.vo.CorporateActionRequest;
import stock.back.service.market.vo.InstrumentReportRequest;
import stock.back.service.market.vo.InitialIssueAllocationRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import stock.back.service.market.vo.OrderBookInstrumentTradingRulesRequest;
import stock.back.service.market.vo.AutoMarketStatusResponse;
import stock.back.service.trading.biz.AccountOrderCleanupService;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock
    private StockInstrumentRepository stockInstrumentRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private StockPriceTickRepository stockPriceTickRepository;

    @Mock
    private StockOrderRepository stockOrderRepository;

    @Mock
    private PortfolioSnapshotRepository portfolioSnapshotRepository;

    @Mock
    private StockAccountCashFlowRepository stockAccountCashFlowRepository;

    @Mock
    private StockAccountRepository stockAccountRepository;

    @Mock
    private StockPriceCacheService stockPriceCacheService;

    @Mock
    private StockAutoMarketConfigRepository stockAutoMarketConfigRepository;

    @Mock
    private StockAutoParticipantProfileConfigRepository stockAutoParticipantProfileConfigRepository;

    @Mock
    private StockAutoParticipantRepository stockAutoParticipantRepository;

    @Mock
    private StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository;

    @Mock
    private StockVirtualMarketConfigRepository stockVirtualMarketConfigRepository;

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;

    @Mock
    private StockExecutionMarketViewRepository stockExecutionMarketViewRepository;

    @Mock
    private StockCorporateActionRepository stockCorporateActionRepository;

    @Mock
    private StockCorporateActionEntitlementRepository stockCorporateActionEntitlementRepository;

    @Mock
    private StockInstrumentReportEventRepository stockInstrumentReportEventRepository;

    @Mock
    private StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Mock
    private AccountOrderCleanupService accountOrderCleanupService;

    private JdbcTemplate commandJdbcTemplate;
    private StubAutoMarketSummaryStatusQuery autoMarketSummaryStatusQuery;
    private MarketServiceTestFacade marketService;

    @BeforeEach
    void setUp() {
        commandJdbcTemplate = createCommandJdbcTemplate();
        autoMarketSummaryStatusQuery = new StubAutoMarketSummaryStatusQuery(jdbcTemplate);
        lenient().when(stockOrderBookInstrumentRepository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> stockOrderBookInstrumentRepository.findById(invocation.getArgument(0)));
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        MarketLedgerFreezeGuard marketLedgerFreezeGuard = mock(MarketLedgerFreezeGuard.class);
        lenient().when(simulationClockService.currentMarketDayStart()).thenReturn(SimulationDayClock.currentDayStart());
        lenient().when(simulationClockService.currentMarketDateTime()).thenAnswer(invocation -> LocalDateTime.now());
        lenient().when(simulationClockService.currentSnapshot()).thenAnswer(invocation -> currentSimulationClockSnapshot());
        SimulationMarketSessionService simulationMarketSessionService =
                new SimulationMarketSessionService(simulationClockService, "00:00", "23:59");
        SimulationClockService listingClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService listingSessionService =
                mock(SimulationMarketSessionService.class);
        LocalDateTime listingNow = LocalDate.now().atTime(5, 0);
        lenient().when(listingClockService.currentSnapshot())
                .thenReturn(pausedSimulationClockSnapshot(listingNow));
        lenient().when(listingSessionService.currentSession())
                .thenReturn(SimulationMarketSession.PRE_OPEN);
        lenient().when(marketLedgerFreezeGuard.acquirePreOpenMutationPermit("order-book instrument listing"))
                .thenReturn(listingNow.toLocalDate());
        AutoMarketStatusDataLoader autoMarketStatusDataLoader = new AutoMarketStatusDataLoader(
                jdbcTemplate,
                stockAutoParticipantSymbolConfigRepository,
                new ListingAutoAccountLedgerQueryService(jdbcTemplate)
        );
        marketService = new MarketServiceTestFacade(
                new OrderBookInstrumentCommandService(
                        stockInstrumentRepository,
                        stockPriceRepository,
                        stockAutoMarketConfigRepository,
                        stockOrderBookInstrumentRepository,
                        stockOrderBookMarketConfigRepository,
                        stockCorporateActionRepository,
                        stockListingAutoAccountConfigRepository,
                        commandJdbcTemplate,
                        listingClockService,
                        listingSessionService,
                        marketLedgerFreezeGuard
                ),
                new MarketCatalogQueryService(
                        stockInstrumentRepository,
                        stockPriceRepository,
                        stockPriceTickRepository,
                        stockOrderBookInstrumentRepository,
                        portfolioSnapshotRepository,
                        stockAccountRepository,
                        stockPriceCacheService
                ),
                new InstrumentReportService(
                        stockOrderBookInstrumentRepository,
                        stockInstrumentReportEventRepository,
                        simulationClockService
                ),
                new AutoParticipantCashAdjustmentService(
                        stockAutoParticipantRepository,
                        stockAccountRepository,
                        stockAccountCashFlowRepository,
                        simulationClockService,
                        marketLedgerFreezeGuard
                ),
                new AutoParticipantManagementService(
                        stockAutoParticipantRepository,
                        stockAutoParticipantProfileConfigRepository,
                        stockAccountRepository,
                        stockAccountCashFlowRepository,
                        new AutoParticipantStrategyTransitionService(
                                accountOrderCleanupService,
                                commandJdbcTemplate
                        ),
                        new AutoParticipantWithdrawalSettlementService(
                                stockAccountRepository,
                                stockAccountCashFlowRepository,
                                new AutoParticipantStrategyTransitionService(
                                        accountOrderCleanupService,
                                        commandJdbcTemplate
                                ),
                                new NamedParameterJdbcTemplate(commandJdbcTemplate)
                        ),
                        simulationClockService,
                        marketLedgerFreezeGuard
                ),
                new AutoParticipantProfileConfigService(stockAutoParticipantProfileConfigRepository),
                new AutoParticipantSymbolConfigService(
                        stockAutoParticipantRepository,
                        stockAutoParticipantSymbolConfigRepository,
                        stockAutoMarketConfigRepository,
                        stockOrderBookInstrumentRepository
                ),
                new AutoMarketConfigService(
                        stockAutoMarketConfigRepository,
                        stockListingAutoAccountConfigRepository,
                        stockOrderBookInstrumentRepository,
                        new ListingAutoAccountLedgerQueryService(jdbcTemplate),
                        simulationClockService,
                        commandJdbcTemplate
                ),
                new MarketStatusService(
                        stockVirtualMarketConfigRepository,
                        stockOrderBookMarketConfigRepository,
                        stockOrderRepository,
                        stockExecutionMarketViewRepository,
                        simulationClockService,
                        simulationMarketSessionService,
                        mock(MarketSessionFenceCommandService.class)
                ),
                new CorporateActionCommandService(
                        stockOrderBookInstrumentRepository,
                        stockCorporateActionRepository,
                        stockPriceRepository,
                        commandJdbcTemplate,
                        simulationClockService
                ),
                new CorporateActionQueryService(
                        stockOrderBookInstrumentRepository,
                        stockCorporateActionRepository,
                        stockAccountRepository,
                        stockCorporateActionEntitlementRepository
                ),
                new AdminFlowQueryService(
                        jdbcTemplate,
                        new AdminSymbolFlowQueryService(jdbcTemplate, stockOrderBookInstrumentRepository, simulationClockService),
                        simulationClockService
                ),
                new AutoParticipantOverviewQueryService(
                        namedParameterJdbcTemplate,
                        autoMarketStatusDataLoader,
                        new AutoParticipantHoldingQueryService(namedParameterJdbcTemplate),
                        new AutoParticipantProfileOverviewQueryService(
                                jdbcTemplate,
                                simulationClockService,
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
                        ),
                        simulationClockService,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
                ),
                new AutoMarketStatusQueryService(
                        stockAutoMarketConfigRepository,
                        stockAutoParticipantProfileConfigRepository,
                        stockAutoParticipantRepository,
                        stockListingAutoAccountConfigRepository,
                        stockOrderRepository,
                        autoMarketStatusDataLoader,
                        autoMarketSummaryStatusQuery,
                        simulationClockService,
                        simulationMarketSessionService
                ),
                new OrderBookMarketStatusQueryService(
                        jdbcTemplate,
                        stockOrderBookMarketConfigRepository,
                        stockOrderBookInstrumentRepository,
                        stockOrderRepository,
                        simulationClockService,
                        simulationMarketSessionService
                ),
                new OrderBookQueryService(jdbcTemplate, stockOrderBookInstrumentRepository, stockOrderRepository, simulationClockService),
                new OrderBookCandleQueryService(jdbcTemplate, stockOrderBookInstrumentRepository, stockPriceRepository, simulationClockService)
        );
        stubDailyRegimeQuery();
    }

    private SimulationClockSnapshot currentSimulationClockSnapshot() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime realDayStart = SimulationDayClock.dayStart(now);
        long elapsedSeconds = java.time.Duration.between(realDayStart, now).toSeconds();
        long simulationSecondsInDay = elapsedSeconds * 86400L / SimulationDayClock.DAY_DURATION.toSeconds();
        LocalDate simulationDate = LocalDate.now();
        return new SimulationClockSnapshot(
                simulationDate,
                simulationDate.atStartOfDay().plusSeconds(simulationSecondsInDay),
                simulationDate.atStartOfDay(),
                now,
                realDayStart,
                (int) SimulationDayClock.DAY_DURATION.toSeconds(),
                true,
                false,
                elapsedSeconds,
                realDayStart,
                now
        );
    }

    private SimulationClockSnapshot pausedSimulationClockSnapshot(LocalDateTime simulationDateTime) {
        LocalDate simulationDate = simulationDateTime.toLocalDate();
        return new SimulationClockSnapshot(
                simulationDate,
                simulationDateTime,
                simulationDate.atStartOfDay(),
                simulationDateTime,
                simulationDate.atStartOfDay(),
                (int) SimulationDayClock.DAY_DURATION.toSeconds(),
                false,
                false,
                0L,
                null,
                null
        );
    }

    private JdbcTemplate createCommandJdbcTemplate() {
        JdbcTemplate template = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:market_service_command_%d;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(System.nanoTime()),
                "sa",
                ""
        ));
        template.execute("""
                create table stock_account (
                    id bigint generated by default as identity primary key,
                    user_key varchar(64) not null,
                    status varchar(32) not null,
                    participant_category varchar(30) not null default 'MANUAL_PARTICIPANT',
                    cash_balance decimal(19, 2) not null,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        template.execute("""
                create table stock_holding (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null,
                    average_price decimal(19, 2) not null,
                    updated_at timestamp not null
                )
                """);
        template.execute("""
                create table stock_order (
                    id bigint generated by default as identity primary key,
                    symbol varchar(20) not null,
                    market_type varchar(32) not null,
                    status varchar(32) not null
                )
                """);
        return template;
    }

    private void insertOpenOrderBookOrder(String symbol) {
        commandJdbcTemplate.update(
                "insert into stock_order(symbol, market_type, status) values (?, 'ORDER_BOOK', 'PENDING')",
                symbol
        );
    }

    private void stubAutoParticipantStatusQuery(AutoParticipantResponse... participants) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            List<Object> rows = new java.util.ArrayList<>();
            for (int index = 0; index < participants.length; index++) {
                AutoParticipantResponse participant = participants[index];
                ResultSet resultSet = mock(ResultSet.class);
                when(resultSet.getString("user_key")).thenReturn(participant.userKey());
                when(resultSet.getString("display_name")).thenReturn(participant.displayName());
                when(resultSet.getBoolean("enabled")).thenReturn(participant.enabled());
                when(resultSet.getString("profile_type")).thenReturn(participant.profileType());
                when(resultSet.getString("behavior_model_version")).thenReturn(participant.behaviorModelVersion());
                when(resultSet.getObject("behavior_seed")).thenReturn(participant.behaviorSeed());
                if (participant.behaviorSeed() != null) {
                    when(resultSet.getString("behavior_seed")).thenReturn(participant.behaviorSeed());
                }
                when(resultSet.getBigDecimal("recurring_cash_amount")).thenReturn(participant.recurringCashAmount());
                when(resultSet.getBigDecimal("recurring_cash_interval_value")).thenReturn(participant.recurringCashIntervalValue());
                when(resultSet.getString("recurring_cash_interval_unit")).thenReturn(participant.recurringCashIntervalUnit());
                when(resultSet.getObject("account_id", Long.class)).thenReturn(participant.accountId());
                when(resultSet.getString("account_status")).thenReturn(participant.accountStatus());
                when(resultSet.getBigDecimal("cash_balance")).thenReturn(participant.cashBalance());
                when(resultSet.getBigDecimal("payday_available_budget")).thenReturn(participant.paydayAvailableBudget());
                when(resultSet.getBigDecimal("dividend_available_budget")).thenReturn(participant.dividendAvailableBudget());
                when(resultSet.getBigDecimal("funding_reserved_amount")).thenReturn(participant.fundingReservedAmount());
                when(resultSet.getBigDecimal("funding_spent_amount")).thenReturn(participant.fundingSpentAmount());
                when(resultSet.getLong("active_funding_budget_count")).thenReturn(participant.activeFundingBudgetCount());
                when(resultSet.getLong("tracked_position_count")).thenReturn(participant.trackedPositionCount());
                when(resultSet.getBigDecimal("average_holding_trading_days")).thenReturn(participant.averageHoldingTradingDays());
                when(resultSet.getLong("average_down_round_count")).thenReturn(participant.averageDownRoundCount());
                when(resultSet.getObject("created_at", LocalDateTime.class)).thenReturn(participant.createdAt());
                when(resultSet.getObject("updated_at", LocalDateTime.class)).thenReturn(participant.updatedAt());
                when(resultSet.getObject("withdrawn_at", LocalDateTime.class)).thenReturn(participant.withdrawnAt());
                rows.add(rowMapper.mapRow(resultSet, index));
            }
            return rows;
        }).when(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.contains("from stock_auto_participant p"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                aryEq(new Object[0])
        );
    }

    private void stubDailyRegimeQuery() {
        lenient().doAnswer(invocation -> List.of()).when(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.contains("from stock_order_book_regime_modifier"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        lenient().doAnswer(invocation -> List.of()).when(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.contains("from stock_order_book_daily_regime"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void createOrderBookInstrument_validRequest_recordsInitialIssuedShares() {
        when(stockInstrumentRepository.existsById("ZQ001")).thenReturn(false);
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(false);
        when(stockOrderBookInstrumentRepository.save(any(StockOrderBookInstrument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.empty());

        var response = marketService.createOrderBookInstrument(
                new OrderBookInstrumentRequest(
                        " zq001 ",
                        "제로큐 주문장",
                        "",
                        new BigDecimal("70000.00"),
                        100000L,
                        new BigDecimal("30.00"),
                        null,
                        new InitialIssueAllocationRequest("LEGACY_FULL_FLOAT", null)
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        ArgumentCaptor<StockListingAutoAccountConfig> listingConfigCaptor = ArgumentCaptor.forClass(StockListingAutoAccountConfig.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        verify(stockListingAutoAccountConfigRepository).save(listingConfigCaptor.capture());
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(response.tradableShares()).isEqualTo(100000L);
        assertThat(response.tickSize()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(response.priceLimitRate()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(response.priceLimitBase()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.INITIAL_ISSUE);
        assertThat(actionCaptor.getValue().getShareQuantity()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getIssuePrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(actionCaptor.getValue().getStatus()).isEqualTo(StockCorporateActionStatus.LISTED);
        assertThat(actionCaptor.getValue().getListedAt()).isNotNull();
        Long accountId = commandJdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                "stock-listing-zq001"
        );
        assertThat(accountId).isNotNull();
        assertThat(commandJdbcTemplate.queryForObject(
                "select quantity from stock_holding where account_id = ? and symbol = ?",
                Long.class,
                accountId,
                "ZQ001"
        )).isEqualTo(100000L);
        assertThat(commandJdbcTemplate.queryForObject(
                "select average_price from stock_holding where account_id = ? and symbol = ?",
                BigDecimal.class,
                accountId,
                "ZQ001"
        )).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(listingConfigCaptor.getValue().getSymbol()).isEqualTo("ZQ001");
        assertThat(listingConfigCaptor.getValue().getUserKey()).isEqualTo("stock-listing-zq001");
        assertThat(listingConfigCaptor.getValue().getDisplayName()).isEqualTo("제로큐 주문장 상장주관사");
        assertThat(listingConfigCaptor.getValue().getMaxOrderQuantity()).isEqualTo(100);
        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.contains("insert into stock_order"),
                org.mockito.ArgumentMatchers.<Object[]>any()
        );
    }

    @Test
    void updateOrderBookInstrumentTradingRules_validRequest_updatesPriceLimitRate() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L,
                new BigDecimal("1.00"),
                new BigDecimal("30.00")
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.empty());

        var response = marketService.updateOrderBookInstrumentTradingRules(
                " zq001 ",
                new OrderBookInstrumentTradingRulesRequest(new BigDecimal("15.00"))
        );

        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.tickSize()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(response.priceLimitRate()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(instrument.getTickSize()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(instrument.getPriceLimitRate()).isEqualByComparingTo(new BigDecimal("15.00"));
    }

    @Test
    void updateOrderBookInstrumentTradingRules_invalidRate_throwsBadRequest() {
        assertThatThrownBy(() -> marketService.updateOrderBookInstrumentTradingRules(
                "ZQ001",
                new OrderBookInstrumentTradingRulesRequest(new BigDecimal("120.00"))
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Price limit rate");

        verify(stockOrderBookInstrumentRepository, never()).findById(any());
    }

    @Test
    void getOrderBookInstruments_loadsPricesInSingleBatch() {
        StockOrderBookInstrument first = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        StockOrderBookInstrument second = StockOrderBookInstrument.listed(
                "ZQ002",
                "제로큐 테스트",
                "ORDERBOOK",
                new BigDecimal("30000.00"),
                50000L
        );
        when(stockOrderBookInstrumentRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(first, second));
        when(stockPriceRepository.findAllById(any())).thenReturn(List.of(
                StockPrice.initial("ZQ001", new BigDecimal("71000.00")),
                StockPrice.initial("ZQ002", new BigDecimal("30500.00"))
        ));

        var response = marketService.getOrderBookInstruments();

        assertThat(response).hasSize(2);
        assertThat(response.get(0).symbol()).isEqualTo("ZQ001");
        assertThat(response.get(0).currentPrice()).isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(response.get(1).symbol()).isEqualTo("ZQ002");
        assertThat(response.get(1).currentPrice()).isEqualByComparingTo(new BigDecimal("30500.00"));
        verify(stockPriceRepository, never()).findById(any());
    }

    @Test
    void updateAutoParticipantSymbolConfig_validRequest_savesParticipantSymbolStrategy() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001")));
        when(stockAutoParticipantSymbolConfigRepository.findById(any())).thenReturn(Optional.empty());
        when(stockAutoParticipantSymbolConfigRepository.save(any(StockAutoParticipantSymbolConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = marketService.updateAutoParticipantSymbolConfig(
                "stock-auto-001",
                "zq001",
                new AutoParticipantSymbolConfigRequest(true, 10)
        );

        ArgumentCaptor<StockAutoParticipantSymbolConfig> configCaptor = ArgumentCaptor.forClass(StockAutoParticipantSymbolConfig.class);
        verify(stockAutoParticipantSymbolConfigRepository).save(configCaptor.capture());
        assertThat(response.userKey()).isEqualTo("stock-auto-001");
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.intensity()).isEqualTo(10);
        assertThat(configCaptor.getValue().getIntensity()).isEqualTo(10);
    }

    @Test
    void updateAutoParticipantSymbolConfig_invalidIntensity_throwsBadRequest() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001")));

        assertThatThrownBy(() -> marketService.updateAutoParticipantSymbolConfig(
                "stock-auto-001",
                "zq001",
                new AutoParticipantSymbolConfigRequest(true, 11)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Intensity must be between 1 and 10");

        verify(stockAutoParticipantSymbolConfigRepository, never()).save(any());
    }

    @Test
    void getAutoMarketStatus_withoutSavedParticipantSymbolConfig_returnsEffectiveFallbackStrategies() {
        StockAutoMarketConfig marketConfig = StockAutoMarketConfig.defaults("ZQ001");
        marketConfig.update(true, 4, 15);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 6, 30, 9, 30);
        when(stockAutoMarketConfigRepository.findAll()).thenReturn(List.of(marketConfig));
        stubAutoParticipantStatusQuery(new AutoParticipantResponse(
                "stock-auto-001",
                "자동 참여자 1",
                true,
                AutoParticipantProfileType.defaultType().name(),
                null,
                null,
                null,
                10L,
                StockAccountStatus.ACTIVE.name(),
                new BigDecimal("123000.00"),
                updatedAt,
                updatedAt,
                null
        ));
        when(stockAutoParticipantSymbolConfigRepository.findByUserKeyInOrderByUserKeyAscSymbolAsc(List.of("stock-auto-001"))).thenReturn(List.of());
        when(stockOrderRepository.countOpenAutoOrders(any(), any())).thenReturn(0L);

        var response = marketService.getAutoMarketStatus();

        assertThat(response.enabled()).isTrue();
        assertThat(response.participants()).hasSize(1);
        assertThat(response.participants().get(0).accountId()).isEqualTo(10L);
        assertThat(response.participants().get(0).accountStatus()).isEqualTo("ACTIVE");
        assertThat(response.participants().get(0).cashBalance()).isEqualByComparingTo(new BigDecimal("123000.00"));
        assertThat(response.participantSymbolConfigs()).hasSize(1);
        assertThat(response.participantSymbolConfigs().get(0).userKey()).isEqualTo("stock-auto-001");
        assertThat(response.participantSymbolConfigs().get(0).symbol()).isEqualTo("ZQ001");
        assertThat(response.participantSymbolConfigs().get(0).intensity()).isEqualTo(5);
        assertThat(response.enabledParticipantCount()).isEqualTo(1L);
        verify(stockAutoParticipantRepository, never()).findByWithdrawnAtIsNullOrderByUserKeyAsc();
        verify(stockAccountRepository, never()).findAllByUserKeyIn(org.mockito.ArgumentMatchers.anyCollection());
        verify(stockAutoParticipantRepository, never()).countByEnabledTrueAndWithdrawnAtIsNull();
    }

    @Test
    void getAutoMarketStatus_withDailyRegime_includesGeneratedRandomValues() throws Exception {
        StockAutoMarketConfig marketConfig = StockAutoMarketConfig.defaults("ZQ001");
        marketConfig.update(true, 5, 90);
        when(stockAutoMarketConfigRepository.findAll()).thenReturn(List.of(marketConfig));
        stubAutoParticipantStatusQuery();
        when(stockOrderRepository.countOpenAutoOrders(any(), any())).thenReturn(0L);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("symbol")).thenReturn("ZQ001");
            when(resultSet.getObject("simulation_trade_date", LocalDate.class)).thenReturn(LocalDate.of(2026, 7, 7));
            when(resultSet.getString("regime_phase")).thenReturn("SLOT_0600");
            when(resultSet.getString("source_regime_phase")).thenReturn("SLOT_0600");
            when(resultSet.getInt("daily_application_count")).thenReturn(3);
            when(resultSet.getInt("prepared_regime_slot_count")).thenReturn(4);
            when(resultSet.getInt("price_pressure")).thenReturn(80);
            when(resultSet.getInt("asset_preference_pressure")).thenReturn(70);
            when(resultSet.getInt("volatility_pressure")).thenReturn(20);
            when(resultSet.getInt("liquidity_pressure")).thenReturn(-20);
            when(resultSet.getInt("execution_aggression_pressure")).thenReturn(40);
            when(resultSet.getLong("seed")).thenReturn(1234567890123456789L);
            when(resultSet.getObject("created_at", LocalDateTime.class)).thenReturn(LocalDateTime.of(2026, 7, 7, 5, 30));
            when(resultSet.getObject("updated_at", LocalDateTime.class)).thenReturn(LocalDateTime.of(2026, 7, 7, 5, 30));
            return List.of(rowMapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.contains("from stock_order_book_daily_regime"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );

        var response = marketService.getAutoMarketStatus(false);

        assertThat(response.configs()).hasSize(1);
        assertThat(response.configs().get(0).dailyRegime()).isNotNull();
        assertThat(response.configs().get(0).dailyRegime().regimePhase()).isEqualTo("SLOT_0600");
        assertThat(response.configs().get(0).dailyRegime().dailyApplicationCount()).isEqualTo(3);
        assertThat(response.configs().get(0).dailyRegime().preparedRegimeSlotCount()).isEqualTo(4);
        assertThat(response.configs().get(0).dailyRegime().pricePressure()).isEqualTo(80);
        assertThat(response.configs().get(0).dailyRegime().assetPreferencePressure()).isEqualTo(70);
        assertThat(response.configs().get(0).dailyRegime().executionAggressionPressure()).isEqualTo(40);
        assertThat(response.configs().get(0).dailyRegime().seed()).isEqualTo("1234567890123456789");
    }

    @Test
    void getAutoMarketStatus_withoutParticipantSymbolConfigExpansion_skipsParticipantSymbolConfigLookup() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 6, 30, 9, 30);
        AutoParticipantResponse participant = new AutoParticipantResponse(
                "stock-auto-001",
                "자동 참여자 1",
                true,
                AutoParticipantProfileType.defaultType().name(),
                null,
                null,
                null,
                null,
                null,
                null,
                updatedAt,
                updatedAt,
                null
        );
        StockAutoMarketConfig marketConfig = StockAutoMarketConfig.defaults("ZQ001");
        when(stockAutoMarketConfigRepository.findAll()).thenReturn(List.of(marketConfig));
        stubAutoParticipantStatusQuery(participant);
        when(stockOrderRepository.countOpenAutoOrders(any(), any())).thenReturn(0L);

        var response = marketService.getAutoMarketStatus(false);

        assertThat(response.participants()).hasSize(1);
        assertThat(response.configs()).hasSize(1);
        assertThat(response.participantSymbolConfigs()).isEmpty();
        assertThat(response.enabledParticipantCount()).isEqualTo(1L);
        verify(stockAutoParticipantRepository, never()).findByWithdrawnAtIsNullOrderByUserKeyAsc();
        verify(stockAccountRepository, never()).findAllByUserKeyIn(org.mockito.ArgumentMatchers.anyCollection());
        verify(stockAutoParticipantSymbolConfigRepository, never()).findByUserKeyInOrderByUserKeyAscSymbolAsc(any());
        verify(stockAutoParticipantRepository, never()).countByEnabledTrueAndWithdrawnAtIsNull();
    }

    @Test
    void getAutoMarketStatus_summaryOnly_returnsCountsWithoutHeavyCollections() {
        stubAutoMarketSummaryQuery(3L, 1L, 40L, 31L, 2L, 0L, 0L, false);

        var response = marketService.getAutoMarketStatus(false, false, false, false, false, false);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(3L);
        assertThat(response.participantCount()).isEqualTo(40L);
        assertThat(response.participantProfileConfigCount()).isEqualTo(AutoParticipantProfileType.values().length);
        assertThat(response.listingAutoAccountCount()).isEqualTo(2L);
        assertThat(response.enabledParticipantCount()).isEqualTo(31L);
        assertThat(response.salaryEligibleParticipantCount()).isEqualTo(11L);
        assertThat(response.openAutoOrderCount()).isZero();
        assertThat(response.todayAutoExecutionCount()).isZero();
        assertThat(response.configs()).isEmpty();
        assertThat(response.participants()).isEmpty();
        assertThat(response.participantSymbolConfigs()).isEmpty();
        assertThat(response.participantProfileConfigs()).isEmpty();
        assertThat(response.listingAutoAccounts()).isEmpty();
        verify(stockAutoMarketConfigRepository, never()).findAll();
        verify(stockAutoParticipantRepository, never()).findByWithdrawnAtIsNullOrderByUserKeyAsc();
        verify(stockAutoParticipantSymbolConfigRepository, never()).findByUserKeyInOrderByUserKeyAscSymbolAsc(any());
        verify(stockAutoParticipantProfileConfigRepository, never()).findAllByOrderByProfileTypeAsc();
        verify(stockListingAutoAccountConfigRepository, never()).findAllByOrderBySymbolAsc();
        verify(stockAutoMarketConfigRepository, never()).count();
        verify(stockAutoMarketConfigRepository, never()).existsByEnabledTrue();
        verify(stockAutoParticipantRepository, never()).countByWithdrawnAtIsNull();
        verify(stockAutoParticipantRepository, never()).countByEnabledTrueAndWithdrawnAtIsNull();
        verify(stockListingAutoAccountConfigRepository, never()).count();
        verify(stockOrderRepository, never()).countOpenAutoOrders(any(), any());
    }

    @Test
    void getAutoMarketStatus_summaryOnlyWithoutSalaryEligibility_skipsSalaryEligibilitySql() {
        stubAutoMarketSummaryQuery(3L, 1L, 40L, 31L, 2L, 0L, 0L, false);
        autoMarketSummaryStatusQuery.salaryEligibleParticipantCount = 0L;

        var response = marketService.getAutoMarketStatus(false, false, false, false, false, false, false, null);

        assertThat(response.salaryEligibleParticipantCount()).isZero();
        assertThat(autoMarketSummaryStatusQuery.lastIncludeSalaryEligibility).isFalse();
    }

    @Test
    void getAutoParticipantOverviews_scopedUserKeys_queryParticipantSeedsBeforeAggregates() {
        when(namedParameterJdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.namedparam.SqlParameterSource>any(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        )).thenReturn(List.of());

        var response = marketService.getAutoParticipantOverviews(
                false,
                List.of("stock-auto-002", "stock-auto-001", "stock-auto-001")
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.namedparam.SqlParameterSource>any(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        );
        assertThat(response).isEmpty();
        assertThat(sqlCaptor.getValue())
                .contains("p.user_key in (:userKeys)")
                .doesNotContain("with scoped_participants as")
                .doesNotContain("join scoped_participants");
        verify(stockAccountRepository, never()).findAllByUserKeyIn(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void getAutoParticipantProfileOverviews_splitsHeavyAggregatesAndGroupsByProfile() {
        JdbcTemplate realJdbcTemplate = createAutoParticipantProfileOverviewJdbcTemplate();
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentMarketDayStart()).thenReturn(SimulationDayClock.currentDayStart());
        when(simulationClockService.currentMarketDateTime()).thenReturn(SimulationDayClock.currentDayStart().plusDays(1));
        AutoParticipantProfileOverviewQueryService service = new AutoParticipantProfileOverviewQueryService(
                realJdbcTemplate,
                simulationClockService,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );
        LocalDateTime simulationDayStart = SimulationDayClock.currentDayStart();
        LocalDateTime lastOrderAt = simulationDayStart.plusMinutes(15);
        LocalDateTime lastTerminalOrderAt = lastOrderAt.plusMinutes(5);
        LocalDateTime lastExecutionAt = simulationDayStart.plusMinutes(20);

        insertProfileOverviewFixture(realJdbcTemplate, lastOrderAt, lastTerminalOrderAt, lastExecutionAt);

        List<AutoParticipantProfileOverviewResponse> response = service.getAutoParticipantProfileOverviews();

        assertThat(response).hasSize(1);
        AutoParticipantProfileOverviewResponse overview = response.get(0);
        assertThat(overview.profileType()).isEqualTo(AutoParticipantProfileType.MOMENTUM_FOLLOWER.name());
        assertThat(overview.totalCount()).isEqualTo(3);
        assertThat(overview.disabledCount()).isEqualTo(1);
        assertThat(overview.estimatedTotalAsset()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(overview.netCashFlow()).isEqualByComparingTo(new BigDecimal("1100.00"));
        assertThat(overview.totalProfit()).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(overview.returnRate()).isEqualByComparingTo(new BigDecimal("36.36363636"));
        assertThat(overview.lastOrderAt()).isEqualTo(lastTerminalOrderAt);
        assertThat(overview.reservedBuyCash()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(overview.openOrderCount()).isEqualTo(2);
        assertThat(overview.openBuyOrderCount()).isEqualTo(1);
        assertThat(overview.openSellOrderCount()).isEqualTo(1);
        assertThat(overview.openBuyQuantity()).isEqualTo(15);
        assertThat(overview.openSellQuantity()).isEqualTo(5);
        assertThat(overview.lastExecutionAt()).isEqualTo(lastExecutionAt);
        assertThat(overview.todayExecutionCount()).isEqualTo(2);
        assertThat(overview.todayBuyQuantity()).isEqualTo(80);
        assertThat(overview.todaySellQuantity()).isEqualTo(20);
        assertThat(overview.todayGrossAmount()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(overview.symbolHoldings()).hasSize(2);
        assertThat(overview.symbolHoldings().get(0).symbol()).isEqualTo("STOCK001");
        assertThat(overview.symbolHoldings().get(0).quantity()).isEqualTo(100);
        assertThat(overview.symbolHoldings().get(1).symbol()).isEqualTo("STOCK002");
        assertThat(overview.symbolHoldings().get(1).quantity()).isEqualTo(50);
    }

    @Test
    void getAutoParticipantHoldings_preservesRequestOrderAndEmptyGroups() {
        JdbcTemplate realJdbcTemplate = createAutoParticipantHoldingJdbcTemplate();
        AutoParticipantHoldingQueryService service = new AutoParticipantHoldingQueryService(
                new NamedParameterJdbcTemplate(realJdbcTemplate)
        );

        List<AutoParticipantHoldingGroupResponse> response = service.getAutoParticipantHoldings(List.of(
                "stock-auto-002",
                " stock-auto-001 ",
                "stock-auto-001"
        ));

        assertThat(response).hasSize(2);
        assertThat(response.get(0).userKey()).isEqualTo("stock-auto-002");
        assertThat(response.get(0).accountId()).isNull();
        assertThat(response.get(0).holdings()).isEmpty();
        assertThat(response.get(1).userKey()).isEqualTo("stock-auto-001");
        assertThat(response.get(1).accountId()).isEqualTo(11L);
        assertThat(response.get(1).holdings()).hasSize(1);
        assertThat(response.get(1).holdings().get(0).symbol()).isEqualTo("STOCK001");
        assertThat(response.get(1).holdings().get(0).availableQuantity()).isEqualTo(90);
        assertThat(response.get(1).holdings().get(0).marketValue()).isEqualByComparingTo(new BigDecimal("110000.00"));
        assertThat(response.get(1).holdings().get(0).unrealizedProfit()).isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    private JdbcTemplate createAutoParticipantHoldingJdbcTemplate() {
        JdbcTemplate template = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:auto_participant_holding_%d;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(System.nanoTime()),
                "sa",
                ""
        ));
        template.execute("""
                create table stock_auto_participant (
                    user_key varchar(64) not null,
                    withdrawn_at timestamp null
                )
                """);
        template.execute("""
                create table stock_account (
                    id bigint not null,
                    user_key varchar(64) not null
                )
                """);
        template.execute("""
                create table stock_holding (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null,
                    average_price decimal(19, 2) not null
                )
                """);
        template.execute("""
                create table stock_price (
                    symbol varchar(20) not null,
                    current_price decimal(19, 2) not null
                )
                """);
        template.update("insert into stock_auto_participant(user_key, withdrawn_at) values (?, null)", "stock-auto-001");
        template.update("insert into stock_auto_participant(user_key, withdrawn_at) values (?, null)", "stock-auto-002");
        template.update("insert into stock_account(id, user_key) values (?, ?)", 11L, "stock-auto-001");
        template.update(
                "insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price) values (?, ?, ?, ?, ?)",
                11L,
                "STOCK001",
                100L,
                10L,
                new BigDecimal("1000.00")
        );
        template.update("insert into stock_price(symbol, current_price) values (?, ?)", "STOCK001", new BigDecimal("1100.00"));
        return template;
    }

    @Test
    void adminFundFlowBreakdownSql_usesParticipantGroupedActiveAccountsCteForAggregates() {
        assertThat(AdminFlowQueryService.FUND_FLOW_BREAKDOWN_SQL)
                .contains("active_accounts as")
                .contains("participant_category")
                .contains("from active_accounts")
                .contains("join active_accounts aa on aa.id = h.account_id")
                .contains("join active_accounts aa on aa.id = f.account_id")
                .contains("join active_accounts aa on aa.id = e.account_id")
                .contains("from stock_execution_account_day_summary e")
                .doesNotContain("join stock_account a on a.id = h.account_id")
                .doesNotContain("join stock_account a on a.id = f.account_id")
                .doesNotContain("join stock_account a on a.id = e.account_id")
                .doesNotContain("from stock_execution e");

        assertThat(AdminFlowQueryService.FUND_FLOW_BREAKDOWN_RECENT_SIMULATION_DAY_SQL)
                .contains("where e.simulation_trade_date = ?")
                .doesNotContain("e.executed_at");
    }

    @Test
    void adminCorporateActionFlowSummarySql_todayCountUsesIndexedDatePredicate() {
        assertThat(AdminFlowQueryService.CORPORATE_ACTION_FLOW_SUMMARY_SQL)
                .contains("cross join")
                .contains("where created_at >= ?")
                .doesNotContain("sum(case when created_at >= ?");
    }

    @Test
    void adminOrderFlowSummarySql_todayCountUsesIndexedDatePredicate() {
        assertThat(AdminFlowQueryService.ORDER_FLOW_SUMMARY_SQL)
                .contains("cross join")
                .contains("where market_type = 'ORDER_BOOK'")
                .contains("and created_at >= ?")
                .contains("and created_at <= ?")
                .doesNotContain("sum(case when created_at >= ?");
    }

    @Test
    void autoParticipantOrderAggregateSql_scopesOpenOrderAggregateByStatusPredicate() {
        assertThat(AutoParticipantAggregateQuerySupport.OPEN_ORDER_AGGREGATE_SQL)
                .contains("from stock_order %s")
                .contains("and market_type = 'ORDER_BOOK'")
                .contains("and status in ('PENDING', 'PARTIALLY_FILLED')")
                .doesNotContain("sum(case when status in");
        assertThat(AutoParticipantAggregateQuerySupport.LAST_ORDER_LOOKUP_SQL)
                .contains("order by created_at desc")
                .contains("limit 1")
                .contains("created_at >= :activityStart")
                .contains("created_at <= :activityEnd")
                .doesNotContainIgnoringCase("as bigint")
                .doesNotContain("status in ('PENDING', 'PARTIALLY_FILLED')");
        assertThat(AutoParticipantAggregateQuerySupport.LAST_ORDER_LOOKUP_ALL_SQL)
                .contains("created_at <= :activityEnd")
                .doesNotContain("created_at >= :activityStart");
    }

    @Test
    void autoParticipantExecutionAggregateSql_todayCountUsesIndexedDatePredicate() {
        assertThat(AutoParticipantAggregateQuerySupport.EXECUTION_AGGREGATE_SQL)
                .contains("from stock_execution_account_day_summary")
                .contains("simulation_trade_date = :todayDate")
                .contains("simulation_trade_date >= :activityStartDate")
                .contains("last_executed_at >= :activityStart")
                .doesNotContain("from stock_execution\n");
        assertThat(AutoParticipantAggregateQuerySupport.EXECUTION_AGGREGATE_ALL_SQL)
                .contains("from stock_execution_account_day_summary")
                .contains("simulation_trade_date <= :activityEndDate")
                .doesNotContain("activityStart");
    }

    @Test
    void getAdminSymbolFlows_limitedPreviewScopesExecutionsToRecentSimulationDayAndSelectedSymbols() {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(8)
        )).thenReturn(List.of());
        when(stockOrderBookInstrumentRepository.count()).thenReturn(42L);

        var response = marketService.getAdminSymbolFlows(8);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(8)
        );
        assertThat(response.totalCount()).isEqualTo(42L);
        assertThat(response.symbolFlows()).isEmpty();
        assertThat(sqlCaptor.getValue())
                .contains("execution_flow as")
                .contains("and executed_at >= ?")
                .contains("and executed_at < ?")
                .contains("selected_symbols as")
                .contains("limit ?")
                .contains("join selected_symbols s on s.symbol = o.symbol")
                .contains("join selected_symbols s on s.symbol = h.symbol")
                .contains("join selected_symbols s on s.symbol = c.symbol")
                .contains("from selected_symbols s");
    }

    @Test
    void getAdminSymbolFlows_allScopeUsesDailySnapshotsAndBoundedCurrentDayExecutions() {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        var response = marketService.getAdminSymbolFlows(0, AdminFundFlowScope.ALL);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        assertThat(response.totalCount()).isZero();
        assertThat(response.symbolFlows()).isEmpty();
        assertThat(sqlCaptor.getValue())
                .doesNotContain("selected_symbols")
                .contains("historical_execution_flow as")
                .contains("from stock_order_book_daily_snapshot candidate")
                .contains("current_execution_flow as")
                .contains("where candidate.simulation_trade_date < ?")
                .contains("and executed_at >= ?")
                .contains("and executed_at < ?")
                .contains("from stock_order_book_instrument i")
                .contains("from stock_order")
                .contains("from stock_holding h")
                .contains("from stock_corporate_action");
        verify(stockOrderBookInstrumentRepository, never()).count();
    }

    @Test
    void getOrderBookCandles_usesExecutionRowsOnlyForPriceAndVolume() {
        when(stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue("ZQ001")).thenReturn(true);
        doAnswer(invocation -> List.of()).when(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.eq("ZQ001"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
        doAnswer(invocation -> List.of(new BigDecimal("70000.00"))).when(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.eq("ZQ001"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );

        var candles = marketService.getOrderBookCandles("zq001", "1M");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.eq("ZQ001"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
        assertThat(sqlCaptor.getValue())
                .contains("from stock_execution")
                .contains("source = 'INTERNAL_ORDER_BOOK'")
                .contains("side = 'BUY'")
                .contains("and executed_at <= ?")
                .contains("sum(quantity) as volume")
                .doesNotContain("from stock_price_tick")
                .doesNotContain("price_time");
        assertThat(candles).hasSize(120);
        assertThat(candles).allSatisfy(candle -> {
            assertThat(candle.volume()).isZero();
            assertThat(candle.executionCount()).isZero();
            assertThat(candle.hasExecution()).isFalse();
            assertThat(candle.openPrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
            assertThat(candle.closePrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        });
    }

    @Test
    void getAutoMarketStatus_profileConfigWithNullBehaviorWeights_returnsDefaultBehaviorWeights() {
        StockAutoParticipantProfileConfig config = StockAutoParticipantProfileConfig.create(
                AutoParticipantProfileType.MOMENTUM_FOLLOWER,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("1.20"),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("0.10"),
                new BigDecimal("0.05"),
                new BigDecimal("0.20"),
                BigDecimal.ZERO,
                30
        );
        when(stockAutoParticipantProfileConfigRepository.findAllByOrderByProfileTypeAsc()).thenReturn(List.of(config));
        when(stockAutoMarketConfigRepository.findAll()).thenReturn(List.of());
        stubAutoParticipantStatusQuery();

        var response = marketService.getAutoMarketStatus();

        var profileConfig = response.participantProfileConfigs().stream()
                .filter(item -> item.profileType().equals("MOMENTUM_FOLLOWER"))
                .findFirst()
                .orElseThrow();
        assertThat(profileConfig.customized()).isTrue();
        assertThat(profileConfig.momentumWeight()).isEqualByComparingTo(new BigDecimal("0.85"));
        assertThat(profileConfig.orderMultiplier()).isEqualByComparingTo(new BigDecimal("1.20"));
    }

    @Test
    void updateAutoParticipantProfileConfig_dividendReinvestorClearsRecurringDeposit() {
        when(stockAutoParticipantProfileConfigRepository.findById(AutoParticipantProfileType.DIVIDEND_REINVESTOR))
                .thenReturn(Optional.empty());
        when(stockAutoParticipantProfileConfigRepository.save(any(StockAutoParticipantProfileConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = marketService.updateAutoParticipantProfileConfig(
                "DIVIDEND_REINVESTOR",
                new AutoParticipantProfileConfigRequest(
                        new BigDecimal("0.70"),
                        new BigDecimal("0.45"),
                        new BigDecimal("0.20"),
                        new BigDecimal("0.30"),
                        new BigDecimal("0.30"),
                        new BigDecimal("0.10"),
                        new BigDecimal("0.20"),
                        new BigDecimal("0.10"),
                        new BigDecimal("0.05"),
                        new BigDecimal("0.35"),
                        new BigDecimal("1.10"),
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        new BigDecimal("0.60"),
                        new BigDecimal("0.40"),
                        new BigDecimal("0.20"),
                        new BigDecimal("50000.00"),
                        new BigDecimal("30"),
                        "DAY",
                        null
                )
        );

        assertThat(response.recurringDepositAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.recurringDepositIntervalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.recurringDepositIntervalUnit()).isEqualTo("DAY");
    }

    @Test
    void getAutoMarketStatus_listingAutoAccount_includesLedgerSnapshot() throws Exception {
        StockListingAutoAccountConfig listingConfig = StockListingAutoAccountConfig.defaults(
                "ZQ001",
                "stock-listing-zq001",
                "제로큐 상장주관사",
                100000L
        );
        when(stockAutoMarketConfigRepository.findAll()).thenReturn(List.of());
        stubAutoParticipantStatusQuery();
        when(stockListingAutoAccountConfigRepository.findAllByOrderBySymbolAsc()).thenReturn(List.of(listingConfig));
        when(stockOrderRepository.countOpenAutoOrders(any(), any())).thenReturn(0L);
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("from stock_listing_auto_account_config c"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                aryEq(new Object[0])
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getString("symbol")).thenReturn("ZQ001");
            when(resultSet.getObject("account_id", Long.class)).thenReturn(77L);
            when(resultSet.getLong("holding_quantity")).thenReturn(100000L);
            when(resultSet.getLong("reserved_quantity")).thenReturn(1200L);
            when(resultSet.getBigDecimal("cash_balance")).thenReturn(new BigDecimal("350000.00"));
            when(resultSet.getBigDecimal("average_price")).thenReturn(new BigDecimal("70000.00"));
            when(resultSet.getBigDecimal("current_price")).thenReturn(new BigDecimal("72000.00"));
            return List.of(rowMapper.mapRow(resultSet, 0));
        });

        var response = marketService.getAutoMarketStatus();

        assertThat(response.listingAutoAccounts()).hasSize(1);
        var listingAccount = response.listingAutoAccounts().get(0);
        assertThat(listingAccount.accountId()).isEqualTo(77L);
        assertThat(listingAccount.holdingQuantity()).isEqualTo(100000L);
        assertThat(listingAccount.reservedQuantity()).isEqualTo(1200L);
        assertThat(listingAccount.availableQuantity()).isEqualTo(98800L);
        assertThat(listingAccount.cashBalance()).isEqualByComparingTo(new BigDecimal("350000.00"));
        assertThat(listingAccount.averagePrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(listingAccount.currentPrice()).isEqualByComparingTo(new BigDecimal("72000.00"));
        assertThat(listingAccount.marketValue()).isEqualByComparingTo(new BigDecimal("7200000000.00"));
        verify(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.contains("from stock_listing_auto_account_config c"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                aryEq(new Object[0])
        );
    }

    private JdbcTemplate createAutoParticipantProfileOverviewJdbcTemplate() {
        JdbcTemplate template = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:auto_participant_profile_overview_%d;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(System.nanoTime()),
                "sa",
                ""
        ));
        template.execute("""
                create table stock_auto_participant (
                    user_key varchar(64) not null,
                    enabled boolean not null,
                    profile_type varchar(40) not null,
                    withdrawn_at timestamp null
                )
                """);
        template.execute("""
                create table stock_account (
                    id bigint not null,
                    user_key varchar(64) not null,
                    cash_balance decimal(19, 2) not null
                )
                """);
        template.execute("""
                create table stock_order (
                    account_id bigint not null,
                    market_type varchar(30) not null,
                    side varchar(10) not null,
                    status varchar(20) not null,
                    quantity bigint not null,
                    filled_quantity bigint not null,
                    reserved_cash decimal(19, 2) not null,
                    created_at timestamp not null
                )
                """);
        template.execute("""
                create table stock_holding (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null,
                    average_price decimal(19, 2) not null
                )
                """);
        template.execute("""
                create table stock_price (
                    symbol varchar(20) not null,
                    current_price decimal(19, 2) not null
                )
                """);
        template.execute("""
                create table stock_account_cash_flow (
                    account_id bigint not null,
                    flow_type varchar(20) not null,
                    amount decimal(19, 2) not null,
                    reason varchar(40) not null
                )
                """);
        template.execute("""
                create table stock_corporate_action_entitlement (
                    account_id bigint not null,
                    subscribed_cash_amount decimal(19, 2),
                    status varchar(20) not null
                )
                """);
        template.execute("""
                create table stock_execution (
                    account_id bigint not null,
                    side varchar(10) not null,
                    quantity bigint not null,
                    gross_amount decimal(19, 2) not null,
                    source varchar(30) not null,
                    executed_at timestamp not null
                )
                """);
        template.execute("""
                create table stock_execution_account_day_summary (
                    simulation_trade_date date not null,
                    account_id bigint not null,
                    execution_count bigint not null,
                    buy_quantity bigint not null,
                    sell_quantity bigint not null,
                    gross_amount decimal(19, 2) not null,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        template.execute("""
                create table stock_auto_participant_symbol_config (
                    user_key varchar(64) not null,
                    enabled boolean not null
                )
                """);
        return template;
    }

    private void insertProfileOverviewFixture(
            JdbcTemplate template,
            LocalDateTime lastOrderAt,
            LocalDateTime lastTerminalOrderAt,
            LocalDateTime lastExecutionAt
    ) {
        template.update("insert into stock_auto_participant(user_key, enabled, profile_type, withdrawn_at) values (?, true, ?, null)", "stock-auto-001", AutoParticipantProfileType.MOMENTUM_FOLLOWER.name());
        template.update("insert into stock_auto_participant(user_key, enabled, profile_type, withdrawn_at) values (?, true, ?, null)", "stock-auto-002", AutoParticipantProfileType.MOMENTUM_FOLLOWER.name());
        template.update("insert into stock_auto_participant(user_key, enabled, profile_type, withdrawn_at) values (?, false, ?, null)", "stock-auto-003", AutoParticipantProfileType.MOMENTUM_FOLLOWER.name());
        template.update("insert into stock_account(id, user_key, cash_balance) values (?, ?, ?)", 11L, "stock-auto-001", new BigDecimal("500.00"));
        template.update("insert into stock_account(id, user_key, cash_balance) values (?, ?, ?)", 12L, "stock-auto-002", new BigDecimal("400.00"));
        template.update(
                "insert into stock_order(account_id, market_type, side, status, quantity, filled_quantity, reserved_cash, created_at) values (?, 'ORDER_BOOK', ?, ?, ?, ?, ?, ?)",
                11L,
                "BUY",
                "PENDING",
                20L,
                5L,
                new BigDecimal("50.00"),
                lastOrderAt
        );
        template.update(
                "insert into stock_order(account_id, market_type, side, status, quantity, filled_quantity, reserved_cash, created_at) values (?, 'ORDER_BOOK', ?, ?, ?, ?, ?, ?)",
                12L,
                "SELL",
                "PARTIALLY_FILLED",
                10L,
                5L,
                BigDecimal.ZERO,
                lastOrderAt.minusMinutes(1)
        );
        template.update(
                "insert into stock_order(account_id, market_type, side, status, quantity, filled_quantity, reserved_cash, created_at) values (?, 'ORDER_BOOK', ?, ?, ?, ?, ?, ?)",
                11L,
                "BUY",
                "FILLED",
                99L,
                99L,
                BigDecimal.ZERO,
                lastTerminalOrderAt
        );
        template.update("insert into stock_price(symbol, current_price) values (?, ?)", "STOCK001", new BigDecimal("3.00"));
        template.update("insert into stock_price(symbol, current_price) values (?, ?)", "STOCK002", new BigDecimal("3.00"));
        template.update("insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price) values (?, ?, ?, ?, ?)", 11L, "STOCK001", 60L, 8L, new BigDecimal("2.70"));
        template.update("insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price) values (?, ?, ?, ?, ?)", 12L, "STOCK001", 40L, 0L, new BigDecimal("2.70"));
        template.update("insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price) values (?, ?, ?, ?, ?)", 12L, "STOCK002", 50L, 2L, new BigDecimal("3.20"));
        template.update("insert into stock_account_cash_flow(account_id, flow_type, amount, reason) values (?, 'DEPOSIT', ?, 'ADMIN_DEPOSIT')", 11L, new BigDecimal("700.00"));
        template.update("insert into stock_account_cash_flow(account_id, flow_type, amount, reason) values (?, 'DEPOSIT', ?, 'ADMIN_DEPOSIT')", 12L, new BigDecimal("500.00"));
        template.update("insert into stock_account_cash_flow(account_id, flow_type, amount, reason) values (?, 'DEPOSIT', ?, 'DIVIDEND_PAYMENT')", 12L, new BigDecimal("200.00"));
        template.update("insert into stock_account_cash_flow(account_id, flow_type, amount, reason) values (?, 'WITHDRAW', ?, 'ADMIN_WITHDRAW')", 12L, new BigDecimal("100.00"));
        template.update("insert into stock_account_cash_flow(account_id, flow_type, amount, reason) values (?, 'WITHDRAW', ?, 'CAPITAL_INCREASE_SUBSCRIPTION')", 11L, new BigDecimal("100.00"));
        template.update("insert into stock_corporate_action_entitlement(account_id, subscribed_cash_amount, status) values (?, ?, 'SUBSCRIBED')", 11L, new BigDecimal("100.00"));
        template.update("insert into stock_execution(account_id, side, quantity, gross_amount, source, executed_at) values (?, ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?)", 11L, "BUY", 80L, new BigDecimal("600.00"), lastExecutionAt);
        template.update("insert into stock_execution(account_id, side, quantity, gross_amount, source, executed_at) values (?, ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?)", 12L, "SELL", 20L, new BigDecimal("300.00"), lastExecutionAt.minusMinutes(1));
        template.update("insert into stock_execution(account_id, side, quantity, gross_amount, source, executed_at) values (?, ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?)", 12L, "BUY", 1L, BigDecimal.ONE, lastExecutionAt.minusHours(3));
        template.update("insert into stock_execution_account_day_summary(simulation_trade_date, account_id, execution_count, buy_quantity, sell_quantity, gross_amount, last_executed_at, updated_at) values (?, ?, 1, 80, 0, 600.00, ?, ?)", lastExecutionAt.toLocalDate(), 11L, lastExecutionAt, lastExecutionAt);
        template.update("insert into stock_execution_account_day_summary(simulation_trade_date, account_id, execution_count, buy_quantity, sell_quantity, gross_amount, last_executed_at, updated_at) values (?, ?, 1, 0, 20, 300.00, ?, ?)", lastExecutionAt.toLocalDate(), 12L, lastExecutionAt.minusMinutes(1), lastExecutionAt);
        template.update("insert into stock_auto_participant_symbol_config(user_key, enabled) values (?, true)", "stock-auto-001");
        template.update("insert into stock_auto_participant_symbol_config(user_key, enabled) values (?, true)", "stock-auto-002");
        template.update("insert into stock_auto_participant_symbol_config(user_key, enabled) values (?, false)", "stock-auto-002");
        template.update("insert into stock_auto_participant_symbol_config(user_key, enabled) values (?, true)", "stock-auto-003");
        template.update("insert into stock_auto_participant_symbol_config(user_key, enabled) values (?, false)", "stock-auto-003");
    }

    @Test
    void upsertAutoParticipant_existingParticipant_updatesProfileOnly() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-001")).thenReturn(Optional.empty());

        var response = marketService.upsertAutoParticipant(
                "stock-auto-001",
                new AutoParticipantRequest("자동 참여자 수정", false, "NEWS_REACTIVE")
        );

        assertThat(response.displayName()).isEqualTo("자동 참여자 수정");
        assertThat(response.enabled()).isFalse();
        assertThat(response.profileType()).isEqualTo("NEWS_REACTIVE");
    }

    @Test
    void upsertAutoParticipant_invalidProfileType_throwsBadRequest() {
        assertThatThrownBy(() -> marketService.upsertAutoParticipant(
                "stock-auto-001",
                new AutoParticipantRequest("자동 참여자 수정", true, "UNKNOWN_PROFILE")
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown auto participant profile type");

        verify(stockAutoParticipantRepository, never()).save(any());
    }

    @Test
    void adjustAutoParticipantCash_deposit_updatesActiveAccountBalance() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        StockAccount account = StockAccount.open("stock-auto-001");
        account.depositCash(new BigDecimal("10000000.00"));
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("stock-auto-001", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));

        var response = marketService.adjustAutoParticipantCash(
                "stock-auto-001",
                new AutoParticipantCashAdjustmentRequest("deposit", new BigDecimal("1000000.00")),
                "stock-admin"
        );

        assertThat(response.userKey()).isEqualTo("stock-auto-001");
        assertThat(response.adjustmentType()).isEqualTo("DEPOSIT");
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("1000000.00"));
        assertThat(response.cashBalance()).isEqualByComparingTo(new BigDecimal("11000000.00"));
        verify(stockAccountCashFlowRepository).save(any(StockAccountCashFlow.class));
    }

    @Test
    void adjustAutoParticipantCash_withdrawWithoutEnoughCash_throwsBadRequest() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        StockAccount account = StockAccount.open("stock-auto-001");
        account.depositCash(new BigDecimal("10000000.00"));
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("stock-auto-001", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> marketService.adjustAutoParticipantCash(
                "stock-auto-001",
                new AutoParticipantCashAdjustmentRequest("WITHDRAW", new BigDecimal("999999999.00")),
                "stock-admin"
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Insufficient auto participant cash balance");
    }

    @Test
    void publishInstrumentReport_validRequest_recordsPublishEvent() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockInstrumentReportEventRepository.save(any(StockInstrumentReportEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = marketService.publishInstrumentReport(
                "zq001",
                new InstrumentReportRequest(
                        "실적 개선 보고서",
                        "수요 회복과 비용 절감이 동시에 반영됐습니다.",
                        8,
                        "수요 회복으로 매수세가 강합니다.",
                        "원가 상승이 둔화되기 전에는 조정 가능성이 있습니다."
                ),
                "admin-user"
        );

        ArgumentCaptor<StockInstrumentReportEvent> eventCaptor = ArgumentCaptor.forClass(StockInstrumentReportEvent.class);
        verify(stockInstrumentReportEventRepository).save(eventCaptor.capture());
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.eventType()).isEqualTo(StockInstrumentReportEventType.PUBLISH);
        assertThat(response.score()).isEqualTo(8);
        assertThat(eventCaptor.getValue().getRiseReason()).contains("수요 회복");
        assertThat(eventCaptor.getValue().getFallReason()).contains("원가 상승");
    }

    @Test
    void publishInstrumentReport_withoutRiseAndFallReasons_recordsOptionalReasonsAsNull() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockInstrumentReportEventRepository.save(any(StockInstrumentReportEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        marketService.publishInstrumentReport(
                "zq001",
                new InstrumentReportRequest(
                        "점수 보고서",
                        "점수와 요약만으로 자동장 평가를 반영합니다.",
                        6,
                        "",
                        null
                ),
                "admin-user"
        );

        ArgumentCaptor<StockInstrumentReportEvent> eventCaptor = ArgumentCaptor.forClass(StockInstrumentReportEvent.class);
        verify(stockInstrumentReportEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRiseReason()).isNull();
        assertThat(eventCaptor.getValue().getFallReason()).isNull();
    }

    @Test
    void updateInstrumentReport_withoutActiveLatestReport_throwsNotFound() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc("ZQ001"))
                .thenReturn(Optional.of(StockInstrumentReportEvent.delete("ZQ001", "deleted", "admin-user")));

        assertThatThrownBy(() -> marketService.updateInstrumentReport(
                "zq001",
                new InstrumentReportRequest("수정 보고서", "요약", 7, "상승 이유", "하락 이유"),
                "admin-user"
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Instrument report not found");

        verify(stockInstrumentReportEventRepository, never()).save(any());
    }

    @Test
    void deleteInstrumentReport_activeLatestReport_recordsDeleteEventAndLatestBecomesNull() {
        StockInstrumentReportEvent latest = StockInstrumentReportEvent.publish(
                "ZQ001",
                "기존 보고서",
                "요약",
                7,
                "상승 이유",
                "하락 이유",
                "admin-user"
        );
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc("ZQ001"))
                .thenReturn(Optional.of(latest))
                .thenReturn(Optional.of(StockInstrumentReportEvent.delete("ZQ001", "Deleted by admin", "admin-user")));
        when(stockInstrumentReportEventRepository.save(any(StockInstrumentReportEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var deleted = marketService.deleteInstrumentReport("zq001", "admin-user");
        var latestResponse = marketService.getLatestInstrumentReport("zq001");

        assertThat(deleted.eventType()).isEqualTo(StockInstrumentReportEventType.DELETE);
        assertThat(latestResponse).isNull();
    }

    @Test
    void applyCorporateAction_paidInCapitalIncrease_recordsScheduledEventWithoutImmediateShareIncrease() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.of(StockPrice.initial("ZQ001", new BigDecimal("70000.00"))));

        var response = marketService.applyCorporateAction(
                "zq001",
                new CorporateActionRequest(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        50000L,
                        new BigDecimal("50000.00"),
                        null,
                        null,
                        LocalDate.now().plusDays(1),
                        LocalDate.now().plusDays(3),
                        LocalDate.now().plusDays(5),
                        null,
                        "운영자 유상증자"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(response.tradableShares()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.PAID_IN_CAPITAL_INCREASE);
        assertThat(actionCaptor.getValue().getShareQuantity()).isEqualTo(50000L);
        assertThat(actionCaptor.getValue().getIssuePrice()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(actionCaptor.getValue().getBasePrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("63333.00"));
    }

    @Test
    void applyCorporateAction_initialIssue_throwsBadRequest() {
        assertThatThrownBy(() -> marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.INITIAL_ISSUE,
                        100000L,
                        new BigDecimal("70000.00"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "초기 발행"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Initial issue is only allowed when creating an instrument");
        verify(stockOrderBookInstrumentRepository, never()).findById(any());
        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_delisting_recordsZeroValueEventWithoutOpenOrderPrecondition() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        LocalDate delistingDate = LocalDate.now().plusDays(3);
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.empty());

        var response = marketService.applyCorporateAction(
                "zq001",
                new CorporateActionRequest(
                        StockCorporateActionType.DELISTING,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        delistingDate,
                        null,
                        "상장폐지"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.enabled()).isTrue();
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.DELISTING);
        assertThat(actionCaptor.getValue().getStatus().name()).isEqualTo("ANNOUNCED");
        assertThat(actionCaptor.getValue().getDelistingDate()).isEqualTo(delistingDate);
        assertThat(actionCaptor.getValue().getDelistingTreatment().name()).isEqualTo("ZERO_VALUE");
    }

    @Test
    void applyCorporateAction_openOrderBookOrders_recordsFutureAnnouncement() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("ZQ001"))
                .thenReturn(Optional.of(StockPrice.initial("ZQ001", new BigDecimal("70000.00"))));
        insertOpenOrderBookOrder("ZQ001");

        marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.CASH_DIVIDEND,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.now().plusDays(1),
                        LocalDate.now().plusDays(3),
                        null,
                        new BigDecimal("1000.00"),
                        "현금배당"
                )
        );

        verify(stockCorporateActionRepository).save(any());
    }

    @Test
    void applyCorporateAction_cashDividendWithListingDate_throwsBadRequest() {
        assertThatThrownBy(() -> marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.CASH_DIVIDEND,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.now().plusDays(1),
                        LocalDate.now().plusDays(3),
                        LocalDate.now().plusDays(5),
                        new BigDecimal("1000.00"),
                        "현금배당"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Cash dividend does not use listingDate");
        verify(stockOrderBookInstrumentRepository, never()).findById(any());
        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_bonusIssueWithIssuePrice_throwsBadRequest() {
        assertThatThrownBy(() -> marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.BONUS_ISSUE,
                        10000L,
                        new BigDecimal("1.00"),
                        null,
                        null,
                        LocalDate.now().plusDays(1),
                        null,
                        LocalDate.now().plusDays(5),
                        null,
                        "무상증자"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Free share distribution does not use issuePrice");
        verify(stockOrderBookInstrumentRepository, never()).findById(any());
        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_stockSplitWithDividendAmount_throwsBadRequest() {
        assertThatThrownBy(() -> marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.STOCK_SPLIT,
                        null,
                        null,
                        1,
                        5,
                        null,
                        null,
                        LocalDate.now().plusDays(5),
                        new BigDecimal("1000.00"),
                        "1:5 액면분할"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Stock split does not use dividendAmount");
        verify(stockOrderBookInstrumentRepository, never()).findById(any());
        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_stockSplit_recordsScheduledEventWithoutImmediateSplit() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.empty());

        var response = marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.STOCK_SPLIT,
                        null,
                        null,
                        1,
                        5,
                        null,
                        null,
                        LocalDate.now().plusDays(5),
                        null,
                        "1:5 액면분할"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(response.tradableShares()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.STOCK_SPLIT);
        assertThat(actionCaptor.getValue().getSplitFrom()).isEqualTo(1);
        assertThat(actionCaptor.getValue().getSplitTo()).isEqualTo(5);
        assertThat(actionCaptor.getValue().getListingDate()).isNotNull();
    }

    @Test
    void applyCorporateAction_cashDividend_recordsScheduledEventWithoutImmediateCashPayment() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.of(StockPrice.initial("ZQ001", new BigDecimal("70000.00"))));

        var response = marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.CASH_DIVIDEND,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.now().plusDays(1),
                        LocalDate.now().plusDays(3),
                        null,
                        new BigDecimal("1000.00"),
                        "현금배당"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(response.tradableShares()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.CASH_DIVIDEND);
        assertThat(actionCaptor.getValue().getDividendAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(actionCaptor.getValue().getBasePrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
    }

    @Test
    void applyCorporateAction_bonusIssue_recordsScheduledEventWithoutImmediateShareIncrease() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.of(StockPrice.initial("ZQ001", new BigDecimal("70000.00"))));

        var response = marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.BONUS_ISSUE,
                        10000L,
                        null,
                        null,
                        null,
                        LocalDate.now().plusDays(1),
                        null,
                        LocalDate.now().plusDays(5),
                        null,
                        "무상증자"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(response.tradableShares()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.BONUS_ISSUE);
        assertThat(actionCaptor.getValue().getShareQuantity()).isEqualTo(10000L);
        assertThat(actionCaptor.getValue().getBasePrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("63636.00"));
    }

    @Test
    void applyCorporateAction_stockDividend_recordsScheduledEventWithoutImmediateShareIncrease() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.of(StockPrice.initial("ZQ001", new BigDecimal("70000.00"))));

        var response = marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.STOCK_DIVIDEND,
                        10000L,
                        null,
                        null,
                        null,
                        LocalDate.now().plusDays(1),
                        null,
                        LocalDate.now().plusDays(5),
                        null,
                        "주식배당"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(response.tradableShares()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.STOCK_DIVIDEND);
        assertThat(actionCaptor.getValue().getShareQuantity()).isEqualTo(10000L);
        assertThat(actionCaptor.getValue().getBasePrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("63636.00"));
    }

    @Test
    void getPrices_cachedPriceExists_usesRedisPriceAndProvider() {
        when(stockPriceRepository.findVirtualMarketPrices())
                .thenReturn(List.of(StockPrice.initial("005930", new BigDecimal("70000.00"))));
        when(stockPriceCacheService.getCachedPrices(List.of("005930")))
                .thenReturn(Map.of("005930", new CachedStockPrice(new BigDecimal("71000.00"), "redis-cache")));

        var prices = marketService.getPrices();

        assertThat(prices).hasSize(1);
        assertThat(prices.get(0).currentPrice()).isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(prices.get(0).changeRate()).isEqualByComparingTo(new BigDecimal("1.4286"));
        assertThat(prices.get(0).provider()).isEqualTo("redis-cache");
    }

    @Test
    void getCorporateActions_existingSymbol_returnsActionResponses() {
        StockCorporateAction action = org.mockito.Mockito.mock(StockCorporateAction.class);
        LocalDate exRightsDate = LocalDate.of(2026, 6, 22);
        LocalDate paymentDate = LocalDate.of(2026, 6, 24);
        LocalDate createdAtDate = LocalDate.of(2026, 6, 20);
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockCorporateActionRepository.findBySymbolOrderByCreatedAtDesc("ZQ001")).thenReturn(List.of(action));
        when(action.getId()).thenReturn(11L);
        when(action.getSymbol()).thenReturn("ZQ001");
        when(action.getActionType()).thenReturn(StockCorporateActionType.CASH_DIVIDEND);
        when(action.getDividendAmount()).thenReturn(new BigDecimal("1000.00"));
        when(action.getStatus()).thenReturn(StockCorporateActionStatus.ANNOUNCED);
        when(action.getBasePrice()).thenReturn(new BigDecimal("70000.00"));
        when(action.getTheoreticalExRightsPrice()).thenReturn(new BigDecimal("69000.00"));
        when(action.getExRightsDate()).thenReturn(exRightsDate);
        when(action.getPaymentDate()).thenReturn(paymentDate);
        when(action.getCreatedAt()).thenReturn(createdAtDate.atStartOfDay());

        var actions = marketService.getCorporateActions(" zq001 ");

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).id()).isEqualTo(11L);
        assertThat(actions.get(0).symbol()).isEqualTo("ZQ001");
        assertThat(actions.get(0).actionType()).isEqualTo(StockCorporateActionType.CASH_DIVIDEND);
        assertThat(actions.get(0).dividendAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(actions.get(0).status()).isEqualTo(StockCorporateActionStatus.ANNOUNCED);
        assertThat(actions.get(0).theoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("69000.00"));
        assertThat(actions.get(0).exRightsDate()).isEqualTo(exRightsDate);
        assertThat(actions.get(0).paymentDate()).isEqualTo(paymentDate);
    }

    @Test
    void getCorporateActions_unknownSymbol_throwsNotFound() {
        when(stockOrderBookInstrumentRepository.existsById("UNKNOWN")).thenReturn(false);

        assertThatThrownBy(() -> marketService.getCorporateActions("unknown"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown order book symbol: UNKNOWN");
    }

    @Test
    void getMyCorporateActionEntitlements_existingRows_returnsJoinedActionType() {
        StockCorporateActionEntitlement entitlement = org.mockito.Mockito.mock(StockCorporateActionEntitlement.class);
        StockCorporateAction action = org.mockito.Mockito.mock(StockCorporateAction.class);
        StockAccount account = org.mockito.Mockito.mock(StockAccount.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 22, 9, 0);
        when(stockAccountRepository.findByUserKeyAndStatus("user-a", StockAccountStatus.ACTIVE)).thenReturn(Optional.of(account));
        when(account.getId()).thenReturn(101L);
        when(stockCorporateActionEntitlementRepository.findTop50ByAccountIdOrderByCreatedAtDesc(101L))
                .thenReturn(List.of(entitlement));
        when(entitlement.getId()).thenReturn(21L);
        when(entitlement.getAccountId()).thenReturn(101L);
        when(entitlement.getActionId()).thenReturn(11L);
        when(entitlement.getSymbol()).thenReturn("ZQ001");
        when(entitlement.getQuantity()).thenReturn(3L);
        when(entitlement.getShareQuantity()).thenReturn(1L);
        when(entitlement.getCashAmount()).thenReturn(null);
        when(entitlement.getStatus()).thenReturn(StockCorporateActionEntitlementStatus.ANNOUNCED);
        when(entitlement.getCreatedAt()).thenReturn(createdAt);
        when(stockCorporateActionRepository.findAllById(List.of(11L))).thenReturn(List.of(action));
        when(action.getId()).thenReturn(11L);
        when(action.getActionType()).thenReturn(StockCorporateActionType.BONUS_ISSUE);

        var entitlements = marketService.getMyCorporateActionEntitlements("user-a");

        assertThat(entitlements).hasSize(1);
        assertThat(entitlements.get(0).id()).isEqualTo(21L);
        assertThat(entitlements.get(0).actionId()).isEqualTo(11L);
        assertThat(entitlements.get(0).symbol()).isEqualTo("ZQ001");
        assertThat(entitlements.get(0).actionType()).isEqualTo(StockCorporateActionType.BONUS_ISSUE);
        assertThat(entitlements.get(0).quantity()).isEqualTo(3L);
        assertThat(entitlements.get(0).shareQuantity()).isEqualTo(1L);
        assertThat(entitlements.get(0).status()).isEqualTo(StockCorporateActionEntitlementStatus.ANNOUNCED);
        assertThat(entitlements.get(0).createdAt()).isEqualTo(createdAt);
    }

    @Test
    void getPrices_cachedPriceMissing_usesDatabasePrice() {
        when(stockPriceRepository.findVirtualMarketPrices())
                .thenReturn(List.of(StockPrice.initial("005930", new BigDecimal("70000.00"))));
        when(stockPriceCacheService.getCachedPrices(List.of("005930"))).thenReturn(Map.of());

        var prices = marketService.getPrices();

        assertThat(prices.get(0).currentPrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(prices.get(0).provider()).isEqualTo("initial-listing");
    }

    @Test
    void getPriceTicks_existingTicks_returnsLatestTickResponses() {
        StockPriceTick tick = org.mockito.Mockito.mock(StockPriceTick.class);
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 30);
        when(tick.getSymbol()).thenReturn("005930");
        when(tick.getPrice()).thenReturn(new BigDecimal("71000.00"));
        when(tick.getProvider()).thenReturn("kis-openapi");
        when(tick.getPriceTime()).thenReturn(priceTime);
        when(stockPriceTickRepository.findTop100BySymbolOrderByPriceTimeDesc("005930")).thenReturn(List.of(tick));

        var ticks = marketService.getPriceTicks("005930");

        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).symbol()).isEqualTo("005930");
        assertThat(ticks.get(0).price()).isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(ticks.get(0).provider()).isEqualTo("kis-openapi");
        assertThat(ticks.get(0).priceTime()).isEqualTo(priceTime);
    }

    @Test
    void getPriceTicks_lowercaseSymbol_normalizesToUppercase() {
        when(stockPriceTickRepository.findTop100BySymbolOrderByPriceTimeDesc("005930")).thenReturn(List.of());

        marketService.getPriceTicks(" 005930 ");

        verify(stockPriceTickRepository).findTop100BySymbolOrderByPriceTimeDesc("005930");
    }

    @Test
    void getPriceTicks_blankSymbol_throwsBadRequest() {
        assertThatThrownBy(() -> marketService.getPriceTicks(" "))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Symbol is required");
    }

    @Test
    void getOrderBook_openLimitOrders_returnsBidAndAskLevels() {
        StockOrderRepository.OrderBookLevelView bid = orderBookLevel("71000.00", 3L, 2L);
        StockOrderRepository.OrderBookLevelView ask = orderBookLevel("73000.00", 4L, 1L);
        when(stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue("005930")).thenReturn(true);
        when(stockOrderRepository.findBidLevels(
                org.mockito.ArgumentMatchers.eq("005930"),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.MarketType.ORDER_BOOK),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.OrderSide.BUY),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.OrderType.LIMIT),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(bid));
        when(stockOrderRepository.findAskLevels(
                org.mockito.ArgumentMatchers.eq("005930"),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.MarketType.ORDER_BOOK),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.OrderSide.SELL),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.OrderType.LIMIT),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(ask));

        var orderBook = marketService.getOrderBook("005930");

        assertThat(orderBook.symbol()).isEqualTo("005930");
        assertThat(orderBook.bids()).hasSize(1);
        assertThat(orderBook.bids().get(0).price()).isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(orderBook.bids().get(0).quantity()).isEqualTo(3L);
        assertThat(orderBook.asks().get(0).price()).isEqualByComparingTo(new BigDecimal("73000.00"));
        assertThat(orderBook.asks().get(0).orderCount()).isEqualTo(1L);
    }

    @Test
    void getOrderBook_lowercaseSymbol_normalizesToUppercase() {
        when(stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue("005930")).thenReturn(true);
        when(stockOrderRepository.findBidLevels(
                org.mockito.ArgumentMatchers.eq("005930"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());
        when(stockOrderRepository.findAskLevels(
                org.mockito.ArgumentMatchers.eq("005930"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        var orderBook = marketService.getOrderBook(" 005930 ");

        assertThat(orderBook.symbol()).isEqualTo("005930");
        verify(stockOrderBookInstrumentRepository).existsBySymbolAndEnabledTrue("005930");
    }

    @Test
    void getOrderBook_unknownSymbol_throwsNotFound() {
        when(stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue("UNKNOWN")).thenReturn(false);

        assertThatThrownBy(() -> marketService.getOrderBook("UNKNOWN"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown stock symbol");
    }

    @Test
    void getRankings_latestSnapshotDate_returnsRankedByReturnRate() {
        LocalDate latestSnapshotDate = LocalDate.of(2026, 6, 16);
        PortfolioSnapshot latestSnapshotMarker = org.mockito.Mockito.mock(PortfolioSnapshot.class);
        when(latestSnapshotMarker.getSnapshotDate()).thenReturn(latestSnapshotDate);
        PortfolioSnapshot first = snapshot("user-a", "10100000.00", "1.0000", latestSnapshotDate);
        PortfolioSnapshot second = snapshot("user-b", "10050000.00", "0.5000", latestSnapshotDate);
        StockAccount firstAccount = account(101L, "user-a");
        StockAccount secondAccount = account(102L, "user-b");
        when(portfolioSnapshotRepository.findTopRankingEligibleByOrderBySnapshotDateDesc())
                .thenReturn(Optional.of(latestSnapshotMarker));
        when(portfolioSnapshotRepository.findTop20BySnapshotDateOrderByReturnRateDesc(latestSnapshotDate))
                .thenReturn(List.of(first, second));
        when(stockAccountRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(firstAccount, secondAccount));

        var rankings = marketService.getRankings();

        assertThat(rankings).hasSize(2);
        assertThat(rankings.get(0).rank()).isEqualTo(1);
        assertThat(rankings.get(0).userKey()).isEqualTo("user-a");
        assertThat(rankings.get(0).displayName()).isEqualTo("투자자 user-a");
        assertThat(rankings.get(0).totalAsset()).isEqualByComparingTo(new BigDecimal("10100000.00"));
        assertThat(rankings.get(0).returnRate()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(rankings.get(0).snapshotDate()).isEqualTo(latestSnapshotDate);
        assertThat(rankings.get(1).rank()).isEqualTo(2);
        assertThat(rankings.get(1).userKey()).isEqualTo("user-b");
        assertThat(rankings.get(1).displayName()).isEqualTo("투자자 user-b");
        verify(stockAccountRepository).findAllById(List.of(101L, 102L));
        verify(stockAccountRepository, never()).findById(any());
    }

    @Test
    void getRankings_noSnapshots_returnsEmptyList() {
        when(portfolioSnapshotRepository.findTopRankingEligibleByOrderBySnapshotDateDesc())
                .thenReturn(Optional.empty());

        var rankings = marketService.getRankings();

        assertThat(rankings).isEmpty();
    }

    @Test
    void getRankings_latestDateWithoutRankingRows_skipsAccountLookup() {
        LocalDate latestSnapshotDate = LocalDate.of(2026, 6, 16);
        PortfolioSnapshot latestSnapshotMarker = org.mockito.Mockito.mock(PortfolioSnapshot.class);
        when(latestSnapshotMarker.getSnapshotDate()).thenReturn(latestSnapshotDate);
        when(portfolioSnapshotRepository.findTopRankingEligibleByOrderBySnapshotDateDesc())
                .thenReturn(Optional.of(latestSnapshotMarker));
        when(portfolioSnapshotRepository.findTop20BySnapshotDateOrderByReturnRateDesc(latestSnapshotDate))
                .thenReturn(List.of());

        var rankings = marketService.getRankings();

        assertThat(rankings).isEmpty();
        verify(stockAccountRepository, never()).findAllById(any());
        verify(stockAccountRepository, never()).findById(any());
    }

    private StockOrderRepository.OrderBookLevelView orderBookLevel(String price, Long quantity, Long orderCount) {
        StockOrderRepository.OrderBookLevelView level = org.mockito.Mockito.mock(StockOrderRepository.OrderBookLevelView.class);
        when(level.getPrice()).thenReturn(new BigDecimal(price));
        when(level.getQuantity()).thenReturn(quantity);
        when(level.getOrderCount()).thenReturn(orderCount);
        return level;
    }

    private void stubAutoMarketSummaryQuery(
            long configCount,
            long enabledConfigCount,
            long participantCount,
            long enabledParticipantCount,
            long listingAutoAccountCount,
            long openAutoOrderCount,
            long todayAutoExecutionCount,
            boolean includeRuntimeMetrics
    ) {
        autoMarketSummaryStatusQuery.configCount = configCount;
        autoMarketSummaryStatusQuery.enabledConfigCount = enabledConfigCount;
        autoMarketSummaryStatusQuery.participantCount = participantCount;
        autoMarketSummaryStatusQuery.enabledParticipantCount = enabledParticipantCount;
        autoMarketSummaryStatusQuery.listingAutoAccountCount = listingAutoAccountCount;
        autoMarketSummaryStatusQuery.salaryEligibleParticipantCount = 11L;
        autoMarketSummaryStatusQuery.openAutoOrderCount = openAutoOrderCount;
        autoMarketSummaryStatusQuery.todayAutoExecutionCount = todayAutoExecutionCount;
        autoMarketSummaryStatusQuery.expectedIncludeRuntimeMetrics = includeRuntimeMetrics;
    }

    private static class StubAutoMarketSummaryStatusQuery extends AutoMarketSummaryStatusQuery {
        private long configCount;
        private long enabledConfigCount;
        private long participantCount;
        private long enabledParticipantCount;
        private long listingAutoAccountCount;
        private long salaryEligibleParticipantCount;
        private long openAutoOrderCount;
        private long todayAutoExecutionCount;
        private Boolean expectedIncludeRuntimeMetrics;
        private Boolean lastIncludeSalaryEligibility;

        private StubAutoMarketSummaryStatusQuery(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate, mock(SimulationClockService.class));
        }

        @Override
        AutoMarketStatusResponse getSummaryStatus(boolean includeRuntimeMetrics, boolean includeSalaryEligibility) {
            if (expectedIncludeRuntimeMetrics != null) {
                assertThat(includeRuntimeMetrics).isEqualTo(expectedIncludeRuntimeMetrics);
            }
            lastIncludeSalaryEligibility = includeSalaryEligibility;
            return new AutoMarketStatusResponse(
                    enabledParticipantCount > 0 && enabledConfigCount > 0,
                    configCount,
                    participantCount,
                    AutoParticipantProfileType.values().length,
                    listingAutoAccountCount,
                    enabledParticipantCount,
                    includeSalaryEligibility ? salaryEligibleParticipantCount : 0L,
                    includeRuntimeMetrics ? openAutoOrderCount : 0L,
                    includeRuntimeMetrics ? todayAutoExecutionCount : 0L,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        @Override
        long countTodayAutoExecutions(java.time.LocalDate simulationTradeDate) {
            return todayAutoExecutionCount;
        }

        @Override
        long countSalaryEligibleAutoParticipants() {
            return salaryEligibleParticipantCount;
        }
    }

    private PortfolioSnapshot snapshot(String userKey, String totalAsset, String returnRate, LocalDate snapshotDate) {
        PortfolioSnapshot snapshot = org.mockito.Mockito.mock(PortfolioSnapshot.class);
        long accountId = "user-a".equals(userKey) ? 101L : 102L;
        when(snapshot.getAccountId()).thenReturn(accountId);
        when(snapshot.getTotalAsset()).thenReturn(new BigDecimal(totalAsset));
        when(snapshot.getReturnRate()).thenReturn(new BigDecimal(returnRate));
        when(snapshot.getSnapshotDate()).thenReturn(snapshotDate);
        return snapshot;
    }

    private StockAccount account(Long id, String userKey) {
        StockAccount account = org.mockito.Mockito.mock(StockAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getUserKey()).thenReturn(userKey);
        return account;
    }
}
