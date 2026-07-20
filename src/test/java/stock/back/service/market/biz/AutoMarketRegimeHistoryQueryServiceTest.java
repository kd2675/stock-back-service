package stock.back.service.market.biz;

import java.time.LocalDate;
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
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketRegimeHistoryResponse.DailyRegime>>any(),
                eq("DEMO001"),
                eq(currentDate)
        )).thenReturn(List.of());
        when(jdbcTemplate.query(
                contains("from stock_order_book_regime_modifier"),
                org.mockito.ArgumentMatchers.<RowMapper<AutoMarketRegimeHistoryResponse.Modifier>>any(),
                eq("DEMO001"),
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
}
