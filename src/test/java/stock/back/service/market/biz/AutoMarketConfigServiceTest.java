package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.ListingAutoPosition;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.ListingAutoAccountRequest;

import java.math.BigDecimal;
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
    private SimulationMarketSessionService simulationMarketSessionService;

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
                simulationMarketSessionService,
                jdbcTemplate
        );
        lenient().when(simulationMarketSessionService.openTime()).thenReturn(java.time.LocalTime.of(6, 0));
        lenient().when(simulationMarketSessionService.closeTime()).thenReturn(java.time.LocalTime.of(18, 0));
    }

    @Test
    void updateAutoMarketConfig_validRequest_savesConfig() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001")));
        when(stockAutoMarketConfigRepository.save(any(StockAutoMarketConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateAutoMarketConfig(
                " zq001 ",
                new AutoMarketConfigUpdateRequest(false, 9, 1000, 30)
        );

        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.enabled()).isFalse();
        assertThat(response.intensity()).isEqualTo(9);
        assertThat(response.maxOrderQuantity()).isEqualTo(1000);
        assertThat(response.orderTtlSeconds()).isEqualTo(30);
        verify(stockAutoMarketConfigRepository).save(any(StockAutoMarketConfig.class));
    }

    @Test
    void updateAutoMarketConfig_invalidIntensity_throwsBadRequest() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001")));

        assertThatThrownBy(() -> service.updateAutoMarketConfig(
                "zq001",
                new AutoMarketConfigUpdateRequest(true, 11, 100, 30)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Intensity must be between 1 and 10");

        verify(stockAutoMarketConfigRepository, never()).save(any());
    }

    @Test
    void regenerateDailyRegime_afterMidSession_updatesMiddayRegime() {
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
                any()
        )).thenReturn(1);
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
        assertThat(response.dailyRegime().regimePhase()).isEqualTo("MIDDAY");
        assertThat(response.dailyRegime().simulationTradeDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 7));
        assertThat(response.dailyRegime().executionAggressionLevel()).isBetween(1, 10);
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
                new ListingAutoAccountRequest(" 신규 상장주관사 ", true, ListingAutoPosition.SELL_ONLY, 50, 20, 2)
        );

        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.displayName()).isEqualTo("신규 상장주관사");
        assertThat(response.accountId()).isEqualTo(77L);
        assertThat(response.availableQuantity()).isEqualTo(80L);
        assertThat(response.marketValue()).isEqualByComparingTo(new BigDecimal("7200000.00"));
        assertThat(response.maxOrderQuantity()).isEqualTo(50);
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
}
