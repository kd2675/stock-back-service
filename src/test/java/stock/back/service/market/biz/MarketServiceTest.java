package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountStatus;
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
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.entity.StockPriceTick;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockInstrumentReportEventRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockPriceTickRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.database.repository.StockVirtualMarketConfigRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;
import stock.back.service.market.vo.CorporateActionRequest;
import stock.back.service.market.vo.InstrumentReportRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private JdbcTemplate jdbcTemplate;

    private MarketService marketService;

    @BeforeEach
    void setUp() {
        marketService = new MarketService(
                stockInstrumentRepository,
                stockPriceRepository,
                stockPriceTickRepository,
                stockOrderRepository,
                portfolioSnapshotRepository,
                stockAccountCashFlowRepository,
                stockAccountRepository,
                stockPriceCacheService,
                stockAutoMarketConfigRepository,
                stockAutoParticipantRepository,
                stockAutoParticipantSymbolConfigRepository,
                stockVirtualMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderBookMarketConfigRepository,
                stockExecutionMarketViewRepository,
                stockCorporateActionRepository,
                stockCorporateActionEntitlementRepository,
                stockInstrumentReportEventRepository,
                jdbcTemplate
        );
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
                        new BigDecimal("30.00")
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
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
                org.mockito.ArgumentMatchers.eq(100000L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("70000.00")),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("insert into stock_order"),
                org.mockito.ArgumentMatchers.eq("listing-ZQ001"),
                org.mockito.ArgumentMatchers.eq(123L),
                org.mockito.ArgumentMatchers.eq("ZQ001"),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("70000.00")),
                org.mockito.ArgumentMatchers.eq(100000L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
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
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        StockAutoMarketConfig marketConfig = StockAutoMarketConfig.defaults("ZQ001");
        marketConfig.update(true, 7, 4, 15);
        when(stockAutoMarketConfigRepository.findAll()).thenReturn(List.of(marketConfig));
        when(stockAutoParticipantRepository.findByWithdrawnAtIsNullOrderByUserKeyAsc()).thenReturn(List.of(participant));
        when(stockAutoParticipantSymbolConfigRepository.findAllByOrderByUserKeyAscSymbolAsc()).thenReturn(List.of());
        when(stockAutoParticipantRepository.countByEnabledTrueAndWithdrawnAtIsNull()).thenReturn(1L);
        when(stockOrderRepository.countOpenAutoOrders(any(), any())).thenReturn(0L);
        when(stockExecutionMarketViewRepository.countAutoExecutionsFrom(any())).thenReturn(0L);

        var response = marketService.getAutoMarketStatus();

        assertThat(response.enabled()).isTrue();
        assertThat(response.participantSymbolConfigs()).hasSize(1);
        assertThat(response.participantSymbolConfigs().get(0).userKey()).isEqualTo("stock-auto-001");
        assertThat(response.participantSymbolConfigs().get(0).symbol()).isEqualTo("ZQ001");
        assertThat(response.participantSymbolConfigs().get(0).intensity()).isEqualTo(7);
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
        when(stockAccountRepository.findByUserKeyAndStatus("stock-auto-001", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        var response = marketService.upsertAutoParticipant(
                "stock-auto-001",
                new AutoParticipantRequest("자동 참여자 수정", false)
        );

        assertThat(response.displayName()).isEqualTo("자동 참여자 수정");
        assertThat(response.enabled()).isFalse();
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
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("69000.00"));
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
        when(portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()).thenReturn(Optional.of(latestSnapshotMarker));
        when(portfolioSnapshotRepository.findTop20BySnapshotDateOrderByReturnRateDesc(latestSnapshotDate))
                .thenReturn(List.of(first, second));

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
    }

    @Test
    void getRankings_noSnapshots_returnsEmptyList() {
        when(portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()).thenReturn(Optional.empty());

        var rankings = marketService.getRankings();

        assertThat(rankings).isEmpty();
    }

    private StockOrderRepository.OrderBookLevelView orderBookLevel(String price, Long quantity, Long orderCount) {
        StockOrderRepository.OrderBookLevelView level = org.mockito.Mockito.mock(StockOrderRepository.OrderBookLevelView.class);
        when(level.getPrice()).thenReturn(new BigDecimal(price));
        when(level.getQuantity()).thenReturn(quantity);
        when(level.getOrderCount()).thenReturn(orderCount);
        return level;
    }

    private PortfolioSnapshot snapshot(String userKey, String totalAsset, String returnRate, LocalDate snapshotDate) {
        PortfolioSnapshot snapshot = org.mockito.Mockito.mock(PortfolioSnapshot.class);
        long accountId = "user-a".equals(userKey) ? 101L : 102L;
        StockAccount account = org.mockito.Mockito.mock(StockAccount.class);
        when(snapshot.getAccountId()).thenReturn(accountId);
        when(account.getUserKey()).thenReturn(userKey);
        when(stockAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(snapshot.getTotalAsset()).thenReturn(new BigDecimal(totalAsset));
        when(snapshot.getReturnRate()).thenReturn(new BigDecimal(returnRate));
        when(snapshot.getSnapshotDate()).thenReturn(snapshotDate);
        return snapshot;
    }
}
