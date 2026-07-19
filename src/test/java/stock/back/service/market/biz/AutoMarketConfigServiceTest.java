package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.ListingAutoPosition;
import stock.back.service.database.entity.ListingAutoPriceDirection;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.AutoMarketDailyRegimeResponse;
import stock.back.service.market.vo.ListingAutoAccountRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoMarketConfigServiceTest {

    @Mock
    private StockAutoMarketConfigRepository stockAutoMarketConfigRepository;

    @Mock
    private StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private ListingAutoAccountLedgerQueryService listingAutoAccountLedgerQueryService;

    @Mock
    private SimulationClockService simulationClockService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AutoMarketConfigService service;

    @BeforeEach
    void setUp() {
        service = new AutoMarketConfigService(
                stockAutoMarketConfigRepository,
                stockListingAutoAccountConfigRepository,
                stockOrderBookInstrumentRepository,
                listingAutoAccountLedgerQueryService,
                simulationClockService,
                jdbcTemplate
        );
    }

    @Test
    void updateAutoMarketConfig_validRequest_savesConfig() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001")));
        when(stockAutoMarketConfigRepository.save(any(StockAutoMarketConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateAutoMarketConfig(
                " zq001 ",
                new AutoMarketConfigUpdateRequest(
                        false,
                        1000,
                        30,
                        new stock.back.service.market.vo.AutoMarketRegimeCountWeightsRequest(10, 20, 50, 20),
                        new stock.back.service.market.vo.AutoMarketDistributionBiasRequest(90, -20, 10, 0, 40),
                        new stock.back.service.market.vo.AutoMarketDistributionBiasRequest(-30, 20, 0, 10, -10)
                )
        );

        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.enabled()).isFalse();
        assertThat(response.primaryDistributionBias().pricePressure()).isEqualTo(90);
        assertThat(response.secondaryDistributionBias().pricePressure()).isEqualTo(-30);
        assertThat(response.primaryRegimeCountWeights().threeTimes()).isEqualTo(50);
        assertThat(response.maxOrderQuantity()).isEqualTo(1000);
        assertThat(response.orderTtlSeconds()).isEqualTo(30);
        verify(stockAutoMarketConfigRepository).save(any(StockAutoMarketConfig.class));
    }

    @Test
    void updateAutoMarketConfig_invalidDistributionBias_throwsBadRequest() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001")));

        assertThatThrownBy(() -> service.updateAutoMarketConfig(
                "zq001",
                new AutoMarketConfigUpdateRequest(
                        true,
                        100,
                        30,
                        null,
                        new stock.back.service.market.vo.AutoMarketDistributionBiasRequest(101, 0, 0, 0, 0),
                        null
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("must be between -100 and 100");

        verify(stockAutoMarketConfigRepository, never()).save(any());
    }

    @Test
    void updateAutoMarketConfig_allRegimeCountWeightsZero_throwsBadRequest() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001"))
                .thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001")));

        assertThatThrownBy(() -> service.updateAutoMarketConfig(
                "zq001",
                new AutoMarketConfigUpdateRequest(
                        true,
                        100,
                        30,
                        new stock.back.service.market.vo.AutoMarketRegimeCountWeightsRequest(0, 0, 0, 0),
                        null,
                        null
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("At least one primary regime count weight");

        verify(stockAutoMarketConfigRepository, never()).save(any());
    }

    @Test
    void regenerateDailyRegime_atTwelveThirty_updatesTwelveOClockSlot() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001")));
        when(simulationClockService.currentMarketDateTime()).thenReturn(LocalDateTime.of(2026, 7, 7, 12, 30));
        when(jdbcTemplate.update(
                org.mockito.ArgumentMatchers.contains("update stock_order_book_daily_regime"),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(1);
        lenient().when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("from stock_order_book_daily_regime"),
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketDailyRegimeResponse>>any(),
                any(),
                any(),
                any()
        )).thenReturn(java.util.List.of());
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("from stock_order_book_regime_modifier"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(java.util.List.of());

        var response = service.regenerateDailyRegime("zq001");

        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.dailyRegime()).isNotNull();
        assertThat(response.dailyRegime().regimePhase()).isEqualTo("SLOT_1200");
        assertThat(response.dailyRegime().simulationTradeDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 7));
        assertThat(response.dailyRegime().executionAggressionPressure()).isBetween(-100, 100);
    }

    @Test
    void regenerateRegimeModifier_existingDailyRegime_updatesCurrentModifierOnly() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001")));
        when(simulationClockService.currentMarketDateTime()).thenReturn(LocalDateTime.of(2026, 7, 7, 12, 42));
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("from stock_order_book_daily_regime"),
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketDailyRegimeResponse>>any(),
                any(),
                any(),
                any()
        )).thenReturn(java.util.List.of(new AutoMarketDailyRegimeResponse(
                "ZQ001",
                LocalDate.of(2026, 7, 7),
                "SLOT_1200",
                "SLOT_0900",
                3,
                4,
                70,
                60,
                10,
                0,
                -20,
                "daily-seed",
                null,
                LocalDateTime.of(2026, 7, 7, 12, 0),
                LocalDateTime.of(2026, 7, 7, 12, 0)
        )));
        when(jdbcTemplate.update(
                org.mockito.ArgumentMatchers.contains("update stock_order_book_regime_modifier"),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(1);

        var response = service.regenerateRegimeModifier("zq001");

        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.dailyRegime()).isNotNull();
        assertThat(response.dailyRegime().seed()).isEqualTo("daily-seed");
        assertThat(response.dailyRegime().sourceRegimePhase()).isEqualTo("SLOT_0900");
        assertThat(response.dailyRegime().dailyApplicationCount()).isEqualTo(3);
        assertThat(response.dailyRegime().preparedRegimeSlotCount()).isEqualTo(4);
        assertThat(response.dailyRegime().currentModifier()).isNotNull();
        assertThat(response.dailyRegime().currentModifier().modifierWindowStartAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 7, 12, 30));
    }

    @Test
    void updateListingAutoAccountConfig_validRequest_returnsLedgerAwareResponse() {
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(
                "ZQ001",
                "stock-listing-zq001",
                "상장주관사",
                100000L
        );
        when(stockListingAutoAccountConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));
        when(listingAutoAccountLedgerQueryService.findLedger(config)).thenReturn(ListingAutoAccountLedger.of(
                77L,
                new BigDecimal("1000000.00"),
                100L,
                20L,
                new BigDecimal("70000.00"),
                new BigDecimal("72000.00")
        ));

        var response = service.updateListingAutoAccountConfig(
                "zq001",
                new ListingAutoAccountRequest(
                        " 신규 상장주관사 ",
                        true,
                        ListingAutoPosition.TWO_SIDED,
                        50,
                        20,
                        2,
                        120L,
                        80L,
                        50000L,
                        120L,
                        ListingAutoPriceDirection.UP,
                        ListingAutoPriceDirection.DOWN
                )
        );

        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.displayName()).isEqualTo("신규 상장주관사");
        assertThat(response.accountId()).isEqualTo(77L);
        assertThat(response.availableQuantity()).isEqualTo(80L);
        assertThat(response.marketValue()).isEqualByComparingTo(new BigDecimal("7200000.00"));
        assertThat(response.maxOrderQuantity()).isEqualTo(50);
        assertThat(response.positionSide()).isEqualTo(ListingAutoPosition.TWO_SIDED);
        assertThat(response.targetBuyQuantity()).isEqualTo(120L);
        assertThat(response.targetSellQuantity()).isEqualTo(80L);
        assertThat(response.targetHoldingQuantity()).isEqualTo(50000L);
        assertThat(response.inventoryBandQuantity()).isEqualTo(120L);
        assertThat(response.buyPriceOffsetDirection()).isEqualTo(ListingAutoPriceDirection.UP);
        assertThat(response.sellPriceOffsetDirection()).isEqualTo(ListingAutoPriceDirection.DOWN);
    }

    @Test
    void updateListingAutoAccountConfig_invalidOrderTtl_throwsBadRequest() {
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(
                "ZQ001",
                "stock-listing-zq001",
                "상장주관사",
                100000L
        );
        when(stockListingAutoAccountConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateListingAutoAccountConfig(
                "zq001",
                new ListingAutoAccountRequest("상장주관사", true, ListingAutoPosition.SELL_ONLY, 50, 0, 2)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Listing auto account order TTL seconds must be positive");

        verify(listingAutoAccountLedgerQueryService, never()).findLedger(any());
    }

    @Test
    void updateListingAutoAccountConfig_twoSidedWithoutBuyTarget_throwsBadRequest() {
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(
                "ZQ001",
                "stock-listing-zq001",
                "상장주관사",
                100000L
        );
        when(stockListingAutoAccountConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateListingAutoAccountConfig(
                "zq001",
                new ListingAutoAccountRequest(
                        "상장주관사",
                        true,
                        ListingAutoPosition.TWO_SIDED,
                        50,
                        20,
                        2,
                        0L,
                        80L,
                        0L,
                        ListingAutoPriceDirection.DOWN,
                        ListingAutoPriceDirection.UP
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("active buy side requires a positive target quantity");
    }

    @Test
    void updateListingAutoAccountConfig_twoSidedWithoutInventoryBand_throwsBadRequest() {
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(
                "ZQ001",
                "stock-listing-zq001",
                "상장주관사",
                100000L
        );
        when(stockListingAutoAccountConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateListingAutoAccountConfig(
                "zq001",
                new ListingAutoAccountRequest(
                        "상장주관사",
                        true,
                        ListingAutoPosition.TWO_SIDED,
                        50,
                        20,
                        2,
                        80L,
                        80L,
                        50000L,
                        0L,
                        ListingAutoPriceDirection.DOWN,
                        ListingAutoPriceDirection.UP
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("requires a positive inventory band quantity");
    }

    @Test
    void updateListingAutoAccountConfig_quoteTargetAboveInventoryBand_throwsBadRequest() {
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(
                "ZQ001",
                "stock-listing-zq001",
                "상장주관사",
                100000L
        );
        when(stockListingAutoAccountConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateListingAutoAccountConfig(
                "zq001",
                new ListingAutoAccountRequest(
                        "상장주관사",
                        true,
                        ListingAutoPosition.TWO_SIDED,
                        50,
                        20,
                        2,
                        120L,
                        80L,
                        50000L,
                        100L,
                        ListingAutoPriceDirection.DOWN,
                        ListingAutoPriceDirection.UP
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("buy quote target cannot exceed inventory band quantity");
    }

    @Test
    void updateListingAutoAccountConfig_quoteTargetNeedsMoreThanTenOrders_throwsBadRequest() {
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(
                "ZQ001",
                "stock-listing-zq001",
                "상장주관사",
                100000L
        );
        when(stockListingAutoAccountConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateListingAutoAccountConfig(
                "zq001",
                new ListingAutoAccountRequest(
                        "상장주관사",
                        true,
                        ListingAutoPosition.TWO_SIDED,
                        50,
                        20,
                        2,
                        501L,
                        500L,
                        50000L,
                        1000L,
                        ListingAutoPriceDirection.DOWN,
                        ListingAutoPriceDirection.UP
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("cannot exceed 10 times max order quantity");
    }

    @Test
    void updateListingAutoAccountConfig_targetHoldingAboveIssuedShares_throwsBadRequest() {
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(
                "ZQ001",
                "stock-listing-zq001",
                "상장주관사",
                100000L
        );
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "주식1",
                "ORDERBOOK",
                new BigDecimal("1000.00"),
                100000L
        );
        when(stockListingAutoAccountConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));

        assertThatThrownBy(() -> service.updateListingAutoAccountConfig(
                "zq001",
                new ListingAutoAccountRequest(
                        "상장주관사",
                        true,
                        ListingAutoPosition.SELL_ONLY,
                        50,
                        20,
                        2,
                        0L,
                        50L,
                        100001L,
                        0L,
                        ListingAutoPriceDirection.DOWN,
                        ListingAutoPriceDirection.UP
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("cannot exceed issued shares");
    }

    @Test
    void updateListingAutoAccountConfig_buyOnlyWithoutTargetHolding_throwsBadRequest() {
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(
                "ZQ001",
                "stock-listing-zq001",
                "상장주관사",
                100000L
        );
        when(stockListingAutoAccountConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateListingAutoAccountConfig(
                "zq001",
                new ListingAutoAccountRequest(
                        "상장주관사",
                        true,
                        ListingAutoPosition.BUY_ONLY,
                        50,
                        20,
                        2,
                        50L,
                        0L,
                        0L,
                        0L,
                        ListingAutoPriceDirection.DOWN,
                        ListingAutoPriceDirection.UP
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("requires a positive target holding quantity");
    }
}
