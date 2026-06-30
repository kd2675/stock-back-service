package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
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
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantHoldingGroupResponse;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantProfileOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;
import stock.back.service.market.vo.CorporateActionRequest;
import stock.back.service.market.vo.InstrumentReportRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

    private MarketService marketService;

    @BeforeEach
    void setUp() {
        marketService = new MarketService(
                new OrderBookInstrumentCommandService(
                        stockInstrumentRepository,
                        stockPriceRepository,
                        stockAutoMarketConfigRepository,
                        stockOrderBookInstrumentRepository,
                        stockOrderBookMarketConfigRepository,
                        stockCorporateActionRepository,
                        stockListingAutoAccountConfigRepository,
                        jdbcTemplate
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
                new InstrumentReportService(stockOrderBookInstrumentRepository, stockInstrumentReportEventRepository),
                new AutoParticipantCashAdjustmentService(
                        stockAutoParticipantRepository,
                        stockAccountRepository,
                        stockAccountCashFlowRepository
                ),
                new AutoParticipantManagementService(
                        stockAutoParticipantRepository,
                        stockAccountRepository,
                        jdbcTemplate
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
                        new ListingAutoAccountLedgerQueryService(jdbcTemplate)
                ),
                new MarketStatusService(
                        stockVirtualMarketConfigRepository,
                        stockOrderBookMarketConfigRepository,
                        stockOrderRepository,
                        stockExecutionMarketViewRepository
                ),
                new CorporateActionCommandService(
                        stockOrderBookInstrumentRepository,
                        stockCorporateActionRepository,
                        stockPriceRepository,
                        jdbcTemplate
                ),
                new CorporateActionQueryService(
                        stockOrderBookInstrumentRepository,
                        stockCorporateActionRepository,
                        stockAccountRepository,
                        stockCorporateActionEntitlementRepository
                ),
                new AdminFlowQueryService(jdbcTemplate, stockOrderBookInstrumentRepository),
                new AutoParticipantOverviewQueryService(jdbcTemplate),
                new AutoMarketStatusQueryService(
                        jdbcTemplate,
                        stockAutoMarketConfigRepository,
                        stockAutoParticipantProfileConfigRepository,
                        stockAutoParticipantRepository,
                        stockAutoParticipantSymbolConfigRepository,
                        stockListingAutoAccountConfigRepository,
                        stockOrderRepository,
                        stockExecutionMarketViewRepository,
                        new ListingAutoAccountLedgerQueryService(jdbcTemplate)
                ),
                new OrderBookMarketStatusQueryService(
                        jdbcTemplate,
                        stockOrderBookMarketConfigRepository,
                        stockOrderBookInstrumentRepository,
                        stockOrderRepository,
                        stockExecutionMarketViewRepository
                ),
                new OrderBookQueryService(jdbcTemplate, stockOrderBookInstrumentRepository, stockOrderRepository),
                new OrderBookCandleQueryService(jdbcTemplate, stockOrderBookInstrumentRepository, stockPriceRepository)
        );
    }

    private void stubAutoParticipantStatusQuery(AutoParticipantResponse... participants) {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("from stock_auto_participant p"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        )).thenAnswer(invocation -> {
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
                when(resultSet.getBigDecimal("recurring_cash_amount")).thenReturn(participant.recurringCashAmount());
                when(resultSet.getBigDecimal("recurring_cash_interval_value")).thenReturn(participant.recurringCashIntervalValue());
                when(resultSet.getString("recurring_cash_interval_unit")).thenReturn(participant.recurringCashIntervalUnit());
                when(resultSet.getObject("account_id", Long.class)).thenReturn(participant.accountId());
                when(resultSet.getString("account_status")).thenReturn(participant.accountStatus());
                when(resultSet.getBigDecimal("cash_balance")).thenReturn(participant.cashBalance());
                when(resultSet.getObject("created_at", LocalDateTime.class)).thenReturn(participant.createdAt());
                when(resultSet.getObject("updated_at", LocalDateTime.class)).thenReturn(participant.updatedAt());
                when(resultSet.getObject("withdrawn_at", LocalDateTime.class)).thenReturn(participant.withdrawnAt());
                rows.add(rowMapper.mapRow(resultSet, index));
            }
            return rows;
        });
    }

    @Test
    void createOrderBookInstrument_validRequest_recordsInitialIssuedShares() {
        when(stockInstrumentRepository.existsById("ZQ001")).thenReturn(false);
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(false);
        when(stockOrderBookInstrumentRepository.save(any(StockOrderBookInstrument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.empty());
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.eq("select id from stock_account where user_key = ?"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq("stock-listing-zq001")
        )).thenReturn(123L);

        var response = marketService.createOrderBookInstrument(
                new OrderBookInstrumentRequest(
                        " zq001 ",
                        "제로큐 주문장",
                        "",
                        new BigDecimal("70000.00"),
                        100000L,
                        new BigDecimal("5.00"),
                        new BigDecimal("30.00"),
                        null
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        ArgumentCaptor<StockListingAutoAccountConfig> listingConfigCaptor = ArgumentCaptor.forClass(StockListingAutoAccountConfig.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        verify(stockListingAutoAccountConfigRepository).save(listingConfigCaptor.capture());
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(response.tradableShares()).isEqualTo(100000L);
        assertThat(response.tickSize()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(response.priceLimitRate()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(response.priceLimitBase()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.INITIAL_ISSUE);
        assertThat(actionCaptor.getValue().getShareQuantity()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getIssuePrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(actionCaptor.getValue().getStatus()).isEqualTo(StockCorporateActionStatus.LISTED);
        assertThat(actionCaptor.getValue().getListedAt()).isNotNull();
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("insert into stock_account"),
                org.mockito.ArgumentMatchers.eq("stock-listing-zq001"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("insert into stock_holding"),
                org.mockito.ArgumentMatchers.eq(123L),
                org.mockito.ArgumentMatchers.eq("ZQ001"),
                org.mockito.ArgumentMatchers.eq(100000L),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("70000.00")),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
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
    void getOrderBookMarketStatus_withoutConfigExpansion_returnsCountsWithoutLoadingConfigs() {
        stubOrderBookMarketSummaryQuery(2L, 3L, 5L, 7L, 1L, true);

        var response = marketService.getOrderBookMarketStatus(false);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(2L);
        assertThat(response.openConfigCount()).isEqualTo(1L);
        assertThat(response.instrumentCount()).isEqualTo(3L);
        assertThat(response.openOrderCount()).isEqualTo(5L);
        assertThat(response.todayExecutionCount()).isEqualTo(7L);
        assertThat(response.configs()).isEmpty();
        verify(stockOrderBookMarketConfigRepository, never()).findAll();
        verify(stockOrderBookMarketConfigRepository, never()).count();
        verify(stockOrderBookInstrumentRepository, never()).countByEnabledTrue();
        verify(stockOrderBookMarketConfigRepository, never()).existsByEnabledTrueAndMarketStatus(any());
        verify(stockOrderRepository, never()).countByMarketTypeAndStatusIn(any(), any());
        verify(stockExecutionMarketViewRepository, never()).countExecutionsFromBySource(any(), any());
    }

    @Test
    void getOrderBookMarketStatus_withoutTodayExecution_skipsTodayExecutionCount() {
        stubOrderBookMarketSummaryQuery(2L, 3L, 5L, 0L, 1L, false);

        var response = marketService.getOrderBookMarketStatus(false, false);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(2L);
        assertThat(response.openConfigCount()).isEqualTo(1L);
        assertThat(response.instrumentCount()).isEqualTo(3L);
        assertThat(response.openOrderCount()).isEqualTo(5L);
        assertThat(response.todayExecutionCount()).isZero();
        assertThat(response.configs()).isEmpty();
        verify(stockExecutionMarketViewRepository, never()).countExecutionsFromBySource(any(), any());
        verify(stockOrderRepository, never()).countByMarketTypeAndStatusIn(any(), any());
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
        marketConfig.update(true, 7, 4, 15);
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
        when(stockExecutionMarketViewRepository.countAutoExecutionsFrom(any())).thenReturn(0L);

        var response = marketService.getAutoMarketStatus();

        assertThat(response.enabled()).isTrue();
        assertThat(response.participants()).hasSize(1);
        assertThat(response.participants().get(0).accountId()).isEqualTo(10L);
        assertThat(response.participants().get(0).accountStatus()).isEqualTo("ACTIVE");
        assertThat(response.participants().get(0).cashBalance()).isEqualByComparingTo(new BigDecimal("123000.00"));
        assertThat(response.participantSymbolConfigs()).hasSize(1);
        assertThat(response.participantSymbolConfigs().get(0).userKey()).isEqualTo("stock-auto-001");
        assertThat(response.participantSymbolConfigs().get(0).symbol()).isEqualTo("ZQ001");
        assertThat(response.participantSymbolConfigs().get(0).intensity()).isEqualTo(7);
        assertThat(response.enabledParticipantCount()).isEqualTo(1L);
        verify(stockAutoParticipantRepository, never()).findByWithdrawnAtIsNullOrderByUserKeyAsc();
        verify(stockAccountRepository, never()).findAllByUserKeyIn(org.mockito.ArgumentMatchers.anyCollection());
        verify(stockAutoParticipantRepository, never()).countByEnabledTrueAndWithdrawnAtIsNull();
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
        when(stockExecutionMarketViewRepository.countAutoExecutionsFrom(any())).thenReturn(0L);

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
        verify(stockExecutionMarketViewRepository, never()).countAutoExecutionsFrom(any());
    }

    @Test
    void getAutoMarketStatus_summaryOnlyWithoutSalaryEligibility_skipsSalaryEligibilitySql() {
        when(jdbcTemplate.queryForObject(
                any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getLong("config_count")).thenReturn(3L);
            when(resultSet.getLong("enabled_config_count")).thenReturn(1L);
            when(resultSet.getLong("participant_count")).thenReturn(40L);
            when(resultSet.getLong("enabled_participant_count")).thenReturn(31L);
            when(resultSet.getLong("listing_auto_account_count")).thenReturn(2L);
            when(resultSet.getLong("salary_eligible_participant_count")).thenReturn(0L);
            when(resultSet.getLong("open_auto_order_count")).thenReturn(0L);
            when(resultSet.getLong("today_auto_execution_count")).thenReturn(0L);
            return rowMapper.mapRow(resultSet, 0);
        });

        var response = marketService.getAutoMarketStatus(false, false, false, false, false, false, false, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        );
        assertThat(response.salaryEligibleParticipantCount()).isZero();
        assertThat(sqlCaptor.getValue())
                .contains("0 as salary_eligible_participant_count")
                .doesNotContain("join stock_account a on a.user_key = p.user_key and a.status = 'ACTIVE'");
    }

    @Test
    void getAutoParticipantOverviews_scopedUserKeys_useScopedParticipantsCteForAggregates() {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(List.of());

        var response = marketService.getAutoParticipantOverviews(
                false,
                List.of("stock-auto-002", "stock-auto-001", "stock-auto-001")
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                any(), any(), any(), any(), any(), any()
        );
        assertThat(response).isEmpty();
        assertThat(sqlCaptor.getValue())
                .contains("with scoped_participants as")
                .contains("p.user_key in (?,?)")
                .contains("join scoped_participants op on op.account_id = o.account_id")
                .contains("sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') then 1 else 0 end) as open_order_count")
                .contains("max(o.created_at) as last_order_at")
                .contains("join scoped_participants hp on hp.account_id = h.account_id")
                .contains("join scoped_participants fp on fp.account_id = f.account_id")
                .contains("join scoped_participants ep on ep.account_id = e.account_id")
                .contains("sum(case when e.executed_at >= ? then 1 else 0 end) as today_execution_count")
                .contains("max(e.executed_at) as last_execution_at")
                .contains("join scoped_participants spc on spc.user_key = sc.user_key")
                .doesNotContain("join scoped_participants lop")
                .doesNotContain("join scoped_participants lep")
                .doesNotContain("oa.user_key in")
                .doesNotContain("ha.user_key in")
                .doesNotContain("fa.user_key in")
                .doesNotContain("ea.user_key in")
                .doesNotContain("sc.user_key in");
        verify(stockAccountRepository, never()).findAllByUserKeyIn(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void getAutoParticipantProfileOverviews_usesScopedParticipantsCteForAggregates() {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Object>>any(),
                any()
        )).thenReturn(List.of());

        var response = marketService.getAutoParticipantProfileOverviews();

        ArgumentCaptor<String> profileSqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                profileSqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Object>>any(),
                any()
        );
        assertThat(response).isEmpty();
        assertThat(profileSqlCaptor.getValue())
                .contains("with scoped_participants as")
                .contains("holding_rows as")
                .contains("join scoped_participants op on op.account_id = o.account_id")
                .contains("group by op.profile_type")
                .contains("sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') then 1 else 0 end) as open_order_count")
                .contains("and o.status in ('PENDING', 'PARTIALLY_FILLED')")
                .contains("select max(o.created_at)")
                .contains("lo.last_order_at")
                .contains("from holding_rows")
                .contains("group by profile_type")
                .contains("join scoped_participants fp on fp.account_id = f.account_id")
                .contains("group by fp.profile_type")
                .contains("join scoped_participants ep on ep.account_id = e.account_id")
                .contains("group by ep.profile_type")
                .contains("and e.executed_at >= ?")
                .contains("count(*) as today_execution_count")
                .contains("select max(e.executed_at)")
                .contains("le.last_execution_at")
                .contains("join scoped_participants sp on sp.user_key = sc.user_key")
                .contains("group by sp.profile_type")
                .contains("row_number() over(partition by grouped.profile_type order by grouped.market_value desc, grouped.symbol asc) as holding_rank")
                .contains("group by profile_type, symbol")
                .contains("sh.symbol as holding_symbol")
                .doesNotContain("join scoped_participants lop")
                .doesNotContain("join scoped_participants lep")
                .doesNotContain("group by o.account_id")
                .doesNotContain("group by e.account_id")
                .doesNotContain("join stock_auto_participant op")
                .doesNotContain("join stock_auto_participant hp")
                .doesNotContain("join stock_auto_participant fp")
                .doesNotContain("join stock_auto_participant ep");
    }

    @Test
    void getAutoParticipantProfileOverviews_groupsSymbolHoldingsByProfile() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        AtomicInteger rowIndex = new AtomicInteger(-1);
        LocalDateTime lastOrderAt = LocalDateTime.of(2026, 6, 30, 9, 15);
        LocalDateTime lastExecutionAt = LocalDateTime.of(2026, 6, 30, 9, 20);

        when(resultSet.next()).thenAnswer(invocation -> rowIndex.incrementAndGet() < 2);
        when(resultSet.getString(any(String.class))).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            int row = rowIndex.get();
            return switch (column) {
                case "profile_type" -> AutoParticipantProfileType.MOMENTUM_FOLLOWER.name();
                case "holding_symbol" -> row == 0 ? "STOCK001" : "STOCK002";
                default -> null;
            };
        });
        when(resultSet.getLong(any(String.class))).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            int row = rowIndex.get();
            return switch (column) {
                case "total_count" -> 3L;
                case "enabled_count" -> 2L;
                case "account_count" -> 2L;
                case "holding_count" -> 2L;
                case "total_holding_quantity" -> 150L;
                case "reserved_sell_quantity" -> 10L;
                case "open_order_count" -> 4L;
                case "open_buy_order_count" -> 3L;
                case "open_sell_order_count" -> 1L;
                case "open_buy_quantity" -> 30L;
                case "open_sell_quantity" -> 5L;
                case "today_execution_count" -> 7L;
                case "today_buy_quantity" -> 80L;
                case "today_sell_quantity" -> 20L;
                case "strategy_count" -> 5L;
                case "enabled_strategy_count" -> 4L;
                case "holding_holder_count" -> row == 0 ? 2L : 1L;
                case "holding_quantity" -> row == 0 ? 100L : 50L;
                case "holding_reserved_quantity" -> row == 0 ? 8L : 2L;
                case "holding_available_quantity" -> row == 0 ? 92L : 48L;
                default -> 0L;
            };
        });
        when(resultSet.getBigDecimal(any(String.class))).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            int row = rowIndex.get();
            return switch (column) {
                case "available_cash" -> new BigDecimal("1000.00");
                case "reserved_buy_cash" -> new BigDecimal("50.00");
                case "holding_market_value" -> new BigDecimal("450.00");
                case "net_cash_flow" -> new BigDecimal("1200.00");
                case "today_gross_amount" -> new BigDecimal("900.00");
                case "holding_market_value_detail" -> row == 0 ? new BigDecimal("300.00") : new BigDecimal("150.00");
                case "holding_unrealized_profit" -> row == 0 ? new BigDecimal("30.00") : new BigDecimal("-10.00");
                default -> BigDecimal.ZERO;
            };
        });
        when(resultSet.getObject(any(String.class), eq(LocalDateTime.class))).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            return switch (column) {
                case "last_order_at" -> lastOrderAt;
                case "last_execution_at" -> lastExecutionAt;
                default -> null;
            };
        });
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultSetExtractor<List<AutoParticipantProfileOverviewResponse>> extractor = invocation.getArgument(1);
            return extractor.extractData(resultSet);
        }).when(jdbcTemplate).query(
                any(String.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<List<AutoParticipantProfileOverviewResponse>>>any(),
                any()
        );

        List<AutoParticipantProfileOverviewResponse> response = marketService.getAutoParticipantProfileOverviews();

        assertThat(response).hasSize(1);
        AutoParticipantProfileOverviewResponse overview = response.get(0);
        assertThat(overview.profileType()).isEqualTo(AutoParticipantProfileType.MOMENTUM_FOLLOWER.name());
        assertThat(overview.totalCount()).isEqualTo(3);
        assertThat(overview.disabledCount()).isEqualTo(1);
        assertThat(overview.estimatedTotalAsset()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(overview.totalProfit()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(overview.returnRate()).isEqualByComparingTo(new BigDecimal("25.0000"));
        assertThat(overview.lastOrderAt()).isEqualTo(lastOrderAt);
        assertThat(overview.lastExecutionAt()).isEqualTo(lastExecutionAt);
        assertThat(overview.symbolHoldings()).hasSize(2);
        assertThat(overview.symbolHoldings().get(0).symbol()).isEqualTo("STOCK001");
        assertThat(overview.symbolHoldings().get(0).quantity()).isEqualTo(100);
        assertThat(overview.symbolHoldings().get(1).symbol()).isEqualTo("STOCK002");
        assertThat(overview.symbolHoldings().get(1).quantity()).isEqualTo(50);
    }

    @Test
    void getAutoParticipantHoldings_usesSingleScopedQueryAndPreservesEmptyGroups() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        AtomicInteger rowIndex = new AtomicInteger(-1);

        when(resultSet.next()).thenAnswer(invocation -> rowIndex.incrementAndGet() < 2);
        when(resultSet.getString(any(String.class))).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            int row = rowIndex.get();
            return switch (column) {
                case "user_key" -> row == 0 ? "stock-auto-001" : "stock-auto-002";
                case "symbol" -> row == 0 ? "STOCK001" : null;
                default -> null;
            };
        });
        when(resultSet.getObject(eq("account_id"), eq(Long.class))).thenAnswer(invocation -> rowIndex.get() == 0 ? 11L : null);
        when(resultSet.getLong(any(String.class))).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            return switch (column) {
                case "quantity" -> 100L;
                case "reserved_quantity" -> 10L;
                case "available_quantity" -> 90L;
                default -> 0L;
            };
        });
        when(resultSet.getBigDecimal(any(String.class))).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            return switch (column) {
                case "average_price" -> new BigDecimal("1000.00");
                case "current_price" -> new BigDecimal("1100.00");
                case "market_value" -> new BigDecimal("110000.00");
                case "unrealized_profit" -> new BigDecimal("10000.00");
                default -> BigDecimal.ZERO;
            };
        });
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultSetExtractor<List<AutoParticipantHoldingGroupResponse>> extractor = invocation.getArgument(1);
            return extractor.extractData(resultSet);
        }).when(jdbcTemplate).query(
                any(String.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<List<AutoParticipantHoldingGroupResponse>>>any(),
                any(), any(), any(), any()
        );

        List<AutoParticipantHoldingGroupResponse> response = marketService.getAutoParticipantHoldings(List.of(
                " stock-auto-001 ",
                "stock-auto-002",
                "stock-auto-001"
        ));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<List<AutoParticipantHoldingGroupResponse>>>any(),
                eq("stock-auto-001"), eq(0), eq("stock-auto-002"), eq(1)
        );
        assertThat(sqlCaptor.getValue())
                .contains("select concat('', ?) as user_key, (? + 0) as request_order")
                .contains("join stock_auto_participant p on p.user_key = r.user_key and p.withdrawn_at is null")
                .contains("left join stock_account a on a.user_key = p.user_key")
                .contains("left join stock_holding h")
                .contains("left join stock_price sp on sp.symbol = h.symbol")
                .contains("order by r.request_order asc, h.symbol asc");
        verify(stockAutoParticipantRepository, never()).findByUserKeyInAndWithdrawnAtIsNull(org.mockito.ArgumentMatchers.anyList());
        verify(stockAccountRepository, never()).findAllByUserKeyIn(org.mockito.ArgumentMatchers.anyCollection());
        assertThat(response).hasSize(2);
        assertThat(response.get(0).userKey()).isEqualTo("stock-auto-001");
        assertThat(response.get(0).accountId()).isEqualTo(11L);
        assertThat(response.get(0).holdings()).hasSize(1);
        assertThat(response.get(0).holdings().get(0).symbol()).isEqualTo("STOCK001");
        assertThat(response.get(0).holdings().get(0).availableQuantity()).isEqualTo(90);
        assertThat(response.get(1).userKey()).isEqualTo("stock-auto-002");
        assertThat(response.get(1).accountId()).isNull();
        assertThat(response.get(1).holdings()).isEmpty();
    }

    @Test
    void getAdminFundFlowSummary_usesActiveAccountsCteForAggregates() {
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getLong("active_account_count")).thenReturn(2L);
            when(resultSet.getBigDecimal("total_cash_balance")).thenReturn(BigDecimal.valueOf(1000));
            when(resultSet.getBigDecimal("total_reserved_buy_cash")).thenReturn(BigDecimal.valueOf(200));
            when(resultSet.getBigDecimal("total_holding_market_value")).thenReturn(BigDecimal.valueOf(300));
            when(resultSet.getBigDecimal("external_deposit_amount")).thenReturn(BigDecimal.valueOf(1500));
            when(resultSet.getBigDecimal("external_withdraw_amount")).thenReturn(BigDecimal.valueOf(100));
            when(resultSet.getBigDecimal("dividend_income_amount")).thenReturn(BigDecimal.valueOf(50));
            when(resultSet.getBigDecimal("buy_net_amount")).thenReturn(BigDecimal.valueOf(700));
            when(resultSet.getBigDecimal("sell_net_amount")).thenReturn(BigDecimal.valueOf(900));
            when(resultSet.getBigDecimal("total_fee_amount")).thenReturn(BigDecimal.valueOf(10));
            when(resultSet.getBigDecimal("total_tax_amount")).thenReturn(BigDecimal.valueOf(5));
            when(resultSet.getBigDecimal("realized_profit")).thenReturn(BigDecimal.valueOf(200));
            when(resultSet.getLong("execution_count")).thenReturn(7L);
            return rowMapper.mapRow(resultSet, 0);
        });

        var response = marketService.getAdminFundFlowSummary();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        );
        assertThat(response.activeAccountCount()).isEqualTo(2L);
        assertThat(sqlCaptor.getValue())
                .contains("with active_accounts as")
                .contains("from active_accounts")
                .contains("join active_accounts aa on aa.id = h.account_id")
                .contains("join active_accounts aa on aa.id = f.account_id")
                .contains("join active_accounts aa on aa.id = e.account_id")
                .doesNotContain("join stock_account a on a.id = h.account_id")
                .doesNotContain("join stock_account a on a.id = f.account_id")
                .doesNotContain("join stock_account a on a.id = e.account_id");
    }

    @Test
    void getAdminFlowOverview_corporateActionTodayCountUsesIndexedDatePredicate() throws Exception {
        when(stockOrderBookInstrumentRepository.count()).thenReturn(0L);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                any()
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getLong(org.mockito.ArgumentMatchers.anyString())).thenReturn(0L);
            return rowMapper.mapRow(resultSet, 0);
        });
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        )).thenReturn(List.of());

        marketService.getAdminFlowOverview(0, false, false);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).queryForObject(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                any()
        );
        String corporateActionSql = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.contains("from stock_corporate_action"))
                .filter(sql -> sql.contains("today_created_count"))
                .findFirst()
                .orElseThrow();
        assertThat(corporateActionSql)
                .contains("cross join")
                .contains("where created_at >= ?")
                .doesNotContain("sum(case when created_at >= ?");
    }

    @Test
    void getAdminSymbolFlows_limitedPreviewScopesHeavyAggregatesToSelectedSymbols() {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.eq(8)
        )).thenReturn(List.of());
        when(stockOrderBookInstrumentRepository.count()).thenReturn(42L);

        var response = marketService.getAdminSymbolFlows(8);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.eq(8)
        );
        assertThat(response.totalCount()).isEqualTo(42L);
        assertThat(response.symbolFlows()).isEmpty();
        assertThat(sqlCaptor.getValue())
                .contains("with execution_flow as")
                .contains("selected_symbols as")
                .contains("limit ?")
                .contains("join selected_symbols s on s.symbol = o.symbol")
                .contains("join selected_symbols s on s.symbol = h.symbol")
                .contains("join selected_symbols s on s.symbol = c.symbol")
                .contains("from selected_symbols s");
    }

    @Test
    void getAdminSymbolFlows_fullViewKeepsAllSymbolAggregatePath() {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        )).thenReturn(List.of());

        var response = marketService.getAdminSymbolFlows(0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        );
        assertThat(response.totalCount()).isZero();
        assertThat(response.symbolFlows()).isEmpty();
        assertThat(sqlCaptor.getValue())
                .doesNotContain("selected_symbols")
                .contains("from stock_order_book_instrument i")
                .contains("from stock_execution")
                .contains("from stock_order")
                .contains("from stock_holding h")
                .contains("from stock_corporate_action");
        verify(stockOrderBookInstrumentRepository, never()).count();
    }

    @Test
    void getOrderBookCandles_usesExecutionRowsOnlyForPriceAndVolume() {
        when(stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue("ZQ001")).thenReturn(true);
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.eq("ZQ001"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        )).thenReturn(List.of());
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<BigDecimal>>any(),
                org.mockito.ArgumentMatchers.eq("ZQ001"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        )).thenReturn(new BigDecimal("70000.00"));

        var candles = marketService.getOrderBookCandles("zq001", "1M");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.eq("ZQ001"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
        assertThat(sqlCaptor.getValue())
                .contains("from stock_execution")
                .contains("source = 'INTERNAL_ORDER_BOOK'")
                .contains("side = 'BUY'")
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
                        new BigDecimal("0.60"),
                        new BigDecimal("0.40"),
                        new BigDecimal("0.20"),
                        new BigDecimal("50000.00"),
                        new BigDecimal("30"),
                        "MINUTE",
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
        when(stockExecutionMarketViewRepository.countAutoExecutionsFrom(any())).thenReturn(0L);
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("from stock_listing_auto_account_config c"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
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
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        );
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
        when(jdbcTemplate.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq("ZQ001")))
                .thenReturn(0L);
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
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("63333.33"));
    }

    @Test
    void applyCorporateAction_additionalIssue_recordsScheduledEventWithoutImmediateShareIncrease() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(jdbcTemplate.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq("ZQ001")))
                .thenReturn(0L);
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.empty());

        var response = marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.ADDITIONAL_ISSUE,
                        30000L,
                        new BigDecimal("60000.00"),
                        null,
                        null,
                        null,
                        null,
                        LocalDate.now().plusDays(5),
                        null,
                        "추가발행"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(response.tradableShares()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.ADDITIONAL_ISSUE);
        assertThat(actionCaptor.getValue().getShareQuantity()).isEqualTo(30000L);
        assertThat(actionCaptor.getValue().getIssuePrice()).isEqualByComparingTo(new BigDecimal("60000.00"));
        assertThat(actionCaptor.getValue().getListingDate()).isNotNull();
    }

    @Test
    void applyCorporateAction_additionalIssueWithoutIssuePrice_throwsBadRequest() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(jdbcTemplate.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq("ZQ001")))
                .thenReturn(0L);

        assertThatThrownBy(() -> marketService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.ADDITIONAL_ISSUE,
                        30000L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.now().plusDays(5),
                        null,
                        "추가발행"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Share issue requires a positive issue price");
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
        verify(jdbcTemplate, never()).queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Long.class), any());
        assertThat(response.enabled()).isTrue();
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.DELISTING);
        assertThat(actionCaptor.getValue().getStatus().name()).isEqualTo("ANNOUNCED");
        assertThat(actionCaptor.getValue().getDelistingDate()).isEqualTo(delistingDate);
        assertThat(actionCaptor.getValue().getDelistingTreatment().name()).isEqualTo("ZERO_VALUE");
    }

    @Test
    void applyCorporateAction_openOrderBookOrders_throwsConflict() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "제로큐 주문장",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(jdbcTemplate.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq("ZQ001")))
                .thenReturn(2L);

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
                        null,
                        new BigDecimal("1000.00"),
                        "현금배당"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Corporate action requires no open order book orders: ZQ001");
        verify(stockCorporateActionRepository, never()).save(any());
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
        when(jdbcTemplate.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq("ZQ001")))
                .thenReturn(0L);
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
        when(jdbcTemplate.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq("ZQ001")))
                .thenReturn(0L);
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
        when(jdbcTemplate.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq("ZQ001")))
                .thenReturn(0L);
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
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("63636.36"));
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
        when(jdbcTemplate.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq("ZQ001")))
                .thenReturn(0L);
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
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("63636.36"));
    }

    @Test
    void getPrices_cachedPriceExists_usesRedisPriceAndProvider() {
        when(stockPriceRepository.findVirtualMarketPrices())
                .thenReturn(List.of(StockPrice.initial("005930", new BigDecimal("70000.00"))));
        when(stockPriceCacheService.getCachedPrice("005930"))
                .thenReturn(Optional.of(new CachedStockPrice(new BigDecimal("71000.00"), "redis-cache")));

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
        when(stockPriceCacheService.getCachedPrice("005930")).thenReturn(Optional.empty());

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
        when(portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()).thenReturn(Optional.of(latestSnapshotMarker));
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
        when(portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()).thenReturn(Optional.empty());

        var rankings = marketService.getRankings();

        assertThat(rankings).isEmpty();
    }

    @Test
    void getRankings_latestDateWithoutRankingRows_skipsAccountLookup() {
        LocalDate latestSnapshotDate = LocalDate.of(2026, 6, 16);
        PortfolioSnapshot latestSnapshotMarker = org.mockito.Mockito.mock(PortfolioSnapshot.class);
        when(latestSnapshotMarker.getSnapshotDate()).thenReturn(latestSnapshotDate);
        when(portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()).thenReturn(Optional.of(latestSnapshotMarker));
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

    private void stubOrderBookMarketSummaryQuery(
            long configCount,
            long instrumentCount,
            long openOrderCount,
            long todayExecutionCount,
            long openConfigCount,
            boolean includeTodayExecution
    ) {
        org.mockito.stubbing.Answer<Object> answer = invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getLong("config_count")).thenReturn(configCount);
            when(resultSet.getLong("instrument_count")).thenReturn(instrumentCount);
            when(resultSet.getLong("open_order_count")).thenReturn(openOrderCount);
            when(resultSet.getLong("today_execution_count")).thenReturn(todayExecutionCount);
            when(resultSet.getLong("open_config_count")).thenReturn(openConfigCount);
            return rowMapper.mapRow(resultSet, 0);
        };
        if (includeTodayExecution) {
            when(jdbcTemplate.queryForObject(
                    any(String.class),
                    org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                    any()
            )).thenAnswer(answer);
            return;
        }
        when(jdbcTemplate.queryForObject(
                any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        )).thenAnswer(answer);
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
        org.mockito.stubbing.Answer<Object> answer = invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getLong("config_count")).thenReturn(configCount);
            when(resultSet.getLong("enabled_config_count")).thenReturn(enabledConfigCount);
            when(resultSet.getLong("participant_count")).thenReturn(participantCount);
            when(resultSet.getLong("enabled_participant_count")).thenReturn(enabledParticipantCount);
            when(resultSet.getLong("listing_auto_account_count")).thenReturn(listingAutoAccountCount);
            when(resultSet.getLong("salary_eligible_participant_count")).thenReturn(11L);
            when(resultSet.getLong("open_auto_order_count")).thenReturn(openAutoOrderCount);
            when(resultSet.getLong("today_auto_execution_count")).thenReturn(todayAutoExecutionCount);
            return rowMapper.mapRow(resultSet, 0);
        };
        if (includeRuntimeMetrics) {
            when(jdbcTemplate.queryForObject(
                    any(String.class),
                    org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                    any()
            )).thenAnswer(answer);
            return;
        }
        when(jdbcTemplate.queryForObject(
                any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        )).thenAnswer(answer);
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
