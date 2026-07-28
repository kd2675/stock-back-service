package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.AutoMarketDailyRegimeResponse;

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
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private SimulationClockService simulationClockService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AutoMarketConfigService service;

    @BeforeEach
    void setUp() {
        service = new AutoMarketConfigService(
                stockAutoMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                simulationClockService,
                jdbcTemplate
        );
    }

    @Test
    void updateAutoMarketConfig_validRequest_savesConfig() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001"))
                .thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001", 4)));
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
    void updateAutoMarketConfig_missingConfig_rebuildsPriceAndFloatNormalizedDefault() {
        StockOrderBookInstrument instrument =
                StockOrderBookInstrument.listedWithTradableShares(
                        "ZQ001",
                        "테스트 종목",
                        "ORDERBOOK",
                        new BigDecimal("15000"),
                        3_000_000L,
                        1_500_000L,
                        BigDecimal.ONE,
                        new BigDecimal("30"),
                        LocalDateTime.of(2026, 7, 7, 5, 0)
                );
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockOrderBookInstrumentRepository.findById("ZQ001"))
                .thenReturn(Optional.of(instrument));
        when(stockAutoMarketConfigRepository.findById("ZQ001"))
                .thenReturn(Optional.empty());
        when(stockAutoMarketConfigRepository.save(any(StockAutoMarketConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateAutoMarketConfig("zq001", null);

        assertThat(response.maxOrderQuantity()).isEqualTo(300);
    }

    @Test
    void updateAutoMarketConfig_invalidDistributionBias_throwsBadRequest() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoMarketConfigRepository.findById("ZQ001"))
                .thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001", 4)));

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
                .thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001", 4)));

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
        when(stockAutoMarketConfigRepository.findById("ZQ001"))
                .thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001", 4)));
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
        when(stockAutoMarketConfigRepository.findById("ZQ001"))
                .thenReturn(Optional.of(StockAutoMarketConfig.defaults("ZQ001", 4)));
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

}
