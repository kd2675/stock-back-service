package stock.back.service.market.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoMarketRegimeHistoryRangeResponse;
import stock.back.service.market.vo.AutoMarketRegimeHistoryResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoMarketRegimeHistoryQueryServiceTest {

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private SimulationClockService simulationClockService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AutoMarketRegimeHistoryQueryService service;

    @BeforeEach
    void setUp() {
        service = new AutoMarketRegimeHistoryQueryService(
                stockOrderBookInstrumentRepository,
                simulationClockService,
                jdbcTemplate
        );
    }

    @Test
    void getHistory_missingTradeDate_usesCurrentSimulationDate() {
        LocalDate currentDate = LocalDate.of(2026, 12, 18);
        when(stockOrderBookInstrumentRepository.existsById("DEMO001")).thenReturn(true);
        when(simulationClockService.currentDate()).thenReturn(currentDate);
        when(jdbcTemplate.query(
                contains("from stock_order_book_daily_regime"),
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketRegimeHistoryQueryService.DatedDailyRegime>>any(),
                eq("DEMO001"),
                eq(currentDate),
                eq(currentDate)
        )).thenReturn(List.of());
        when(jdbcTemplate.query(
                contains("from stock_order_book_regime_modifier"),
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketRegimeHistoryQueryService.DatedModifier>>any(),
                eq("DEMO001"),
                eq(currentDate),
                eq(currentDate)
        )).thenReturn(List.of());

        AutoMarketRegimeHistoryResponse response = service.getHistory(" demo001 ", null);

        assertThat(response.symbol()).isEqualTo("DEMO001");
        assertThat(response.simulationTradeDate()).isEqualTo(currentDate);
        assertThat(response.currentSimulationTradeDate()).isEqualTo(currentDate);
        assertThat(response.dailyRegimes()).isEmpty();
        assertThat(response.modifiers()).isEmpty();
    }

    @Test
    void getHistory_unknownSymbol_throwsNotFoundWithoutQueryingHistory() {
        when(stockOrderBookInstrumentRepository.existsById("UNKNOWN")).thenReturn(false);

        assertThatThrownBy(() -> service.getHistory("unknown", LocalDate.of(2026, 12, 18)))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown order book symbol");

        verify(simulationClockService, never()).currentDate();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void getHistoryRange_sevenDays_groupsRecordsAndMarksMissingDays() {
        LocalDate rangeEndDate = LocalDate.of(2027, 1, 15);
        LocalDate rangeStartDate = rangeEndDate.minusDays(6);
        LocalDate completeDate = rangeEndDate.minusDays(1);
        LocalDateTime currentSimulationDateTime = rangeEndDate.atTime(15, 17);
        AutoMarketRegimeHistoryResponse.DailyRegime dailyRegime = new AutoMarketRegimeHistoryResponse.DailyRegime(
                "SLOT_0600",
                "SLOT_0600",
                -12,
                -45,
                -27,
                -11,
                9,
                "1",
                completeDate.atTime(5, 30),
                completeDate.atTime(5, 30)
        );
        List<AutoMarketRegimeHistoryQueryService.DatedDailyRegime> dailyRows = List.of(
                new AutoMarketRegimeHistoryQueryService.DatedDailyRegime(completeDate, dailyRegime),
                new AutoMarketRegimeHistoryQueryService.DatedDailyRegime(completeDate, withPhase(dailyRegime, "SLOT_0900")),
                new AutoMarketRegimeHistoryQueryService.DatedDailyRegime(completeDate, withPhase(dailyRegime, "SLOT_1200")),
                new AutoMarketRegimeHistoryQueryService.DatedDailyRegime(completeDate, withPhase(dailyRegime, "SLOT_1500"))
        );
        List<AutoMarketRegimeHistoryQueryService.DatedModifier> modifierRows = java.util.stream.IntStream.range(0, 24)
                .mapToObj(index -> new AutoMarketRegimeHistoryQueryService.DatedModifier(
                        completeDate,
                        new AutoMarketRegimeHistoryResponse.Modifier(
                                phaseForWindow(index),
                                completeDate.atTime(6, 0).plusMinutes(index * 30L),
                                0,
                                0,
                                0,
                                0,
                                0,
                                Integer.toString(index),
                                completeDate.atTime(5, 30),
                                completeDate.atTime(5, 30)
                        )
                ))
                .toList();
        when(stockOrderBookInstrumentRepository.existsById("DEMO002")).thenReturn(true);
        when(simulationClockService.currentMarketDateTime()).thenReturn(currentSimulationDateTime);
        when(jdbcTemplate.query(
                contains("from stock_order_book_daily_regime"),
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketRegimeHistoryQueryService.DatedDailyRegime>>any(),
                eq("DEMO002"),
                eq(rangeStartDate),
                eq(rangeEndDate)
        )).thenReturn(dailyRows);
        when(jdbcTemplate.query(
                contains("from stock_order_book_regime_modifier"),
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketRegimeHistoryQueryService.DatedModifier>>any(),
                eq("DEMO002"),
                eq(rangeStartDate),
                eq(rangeEndDate)
        )).thenReturn(modifierRows);

        AutoMarketRegimeHistoryRangeResponse response = service.getHistoryRange("demo002", rangeEndDate);

        assertThat(response.rangeStartDate()).isEqualTo(rangeStartDate);
        assertThat(response.rangeEndDate()).isEqualTo(rangeEndDate);
        assertThat(response.currentSimulationDateTime()).isEqualTo(currentSimulationDateTime);
        assertThat(response.days()).hasSize(7);
        assertThat(response.days().get(5).sourceStatus())
                .isEqualTo(AutoMarketRegimeHistoryRangeResponse.SourceStatus.COMPLETE);
        assertThat(response.days().get(5).dailyApplicationCount()).isEqualTo(4);
        assertThat(response.days().get(5).availableWindowCount()).isEqualTo(24);
        assertThat(response.days().get(6).sourceStatus())
                .isEqualTo(AutoMarketRegimeHistoryRangeResponse.SourceStatus.MISSING);
        verify(jdbcTemplate).query(
                contains("from stock_order_book_daily_regime"),
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketRegimeHistoryQueryService.DatedDailyRegime>>any(),
                eq("DEMO002"),
                eq(rangeStartDate),
                eq(rangeEndDate)
        );
        verify(jdbcTemplate).query(
                contains("from stock_order_book_regime_modifier"),
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketRegimeHistoryQueryService.DatedModifier>>any(),
                eq("DEMO002"),
                eq(rangeStartDate),
                eq(rangeEndDate)
        );
    }

    @Test
    void getHistoryRange_futureEndDate_rejectsWithoutHistoryQueries() {
        LocalDate currentDate = LocalDate.of(2027, 1, 15);
        when(stockOrderBookInstrumentRepository.existsById("DEMO001")).thenReturn(true);
        when(simulationClockService.currentMarketDateTime()).thenReturn(currentDate.atTime(12, 0));

        assertThatThrownBy(() -> service.getHistoryRange("DEMO001", currentDate.plusDays(1)))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("cannot be after");

        verifyNoInteractions(jdbcTemplate);
    }

    private AutoMarketRegimeHistoryResponse.DailyRegime withPhase(
            AutoMarketRegimeHistoryResponse.DailyRegime source,
            String phase
    ) {
        return new AutoMarketRegimeHistoryResponse.DailyRegime(
                phase,
                phase,
                source.pricePressure(),
                source.assetPreferencePressure(),
                source.volatilityPressure(),
                source.liquidityPressure(),
                source.executionAggressionPressure(),
                source.seed(),
                source.createdAt(),
                source.updatedAt()
        );
    }

    private String phaseForWindow(int index) {
        if (index < 6) {
            return "SLOT_0600";
        }
        if (index < 12) {
            return "SLOT_0900";
        }
        if (index < 18) {
            return "SLOT_1200";
        }
        return "SLOT_1500";
    }
}
