package stock.back.service.market.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoMarketRegimeHistoryResponse;

@Service
@RequiredArgsConstructor
public class AutoMarketRegimeHistoryQueryService {

    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final SimulationClockService simulationClockService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public AutoMarketRegimeHistoryResponse getHistory(String symbol, LocalDate requestedTradeDate) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }

        LocalDate currentSimulationTradeDate = simulationClockService.currentDate();
        LocalDate simulationTradeDate = requestedTradeDate == null
                ? currentSimulationTradeDate
                : requestedTradeDate;
        List<AutoMarketRegimeHistoryResponse.DailyRegime> dailyRegimes = loadDailyRegimes(
                normalizedSymbol,
                simulationTradeDate
        );
        List<AutoMarketRegimeHistoryResponse.Modifier> modifiers = loadModifiers(
                normalizedSymbol,
                simulationTradeDate
        );
        int dailyApplicationCount = (int) dailyRegimes.stream()
                .filter(regime -> regime.regimePhase().equals(regime.sourceRegimePhase()))
                .count();

        return new AutoMarketRegimeHistoryResponse(
                normalizedSymbol,
                simulationTradeDate,
                currentSimulationTradeDate,
                dailyApplicationCount,
                dailyRegimes.size(),
                dailyRegimes,
                modifiers
        );
    }

    private List<AutoMarketRegimeHistoryResponse.DailyRegime> loadDailyRegimes(
            String symbol,
            LocalDate simulationTradeDate
    ) {
        return jdbcTemplate.query(
                """
                select regime_phase,
                       coalesce(source_regime_phase, regime_phase) as source_regime_phase,
                       price_pressure,
                       asset_preference_pressure,
                       volatility_pressure,
                       liquidity_pressure,
                       execution_aggression_pressure,
                       seed,
                       created_at,
                       updated_at
                  from stock_order_book_daily_regime
                 where symbol = ?
                   and simulation_trade_date = ?
                 order by case regime_phase
                     when 'SLOT_0600' then 1
                     when 'SLOT_0900' then 2
                     when 'SLOT_1200' then 3
                     when 'SLOT_1500' then 4
                     else 5
                 end
                """,
                (rs, rowNum) -> new AutoMarketRegimeHistoryResponse.DailyRegime(
                        rs.getString("regime_phase"),
                        rs.getString("source_regime_phase"),
                        rs.getInt("price_pressure"),
                        rs.getInt("asset_preference_pressure"),
                        rs.getInt("volatility_pressure"),
                        rs.getInt("liquidity_pressure"),
                        rs.getInt("execution_aggression_pressure"),
                        Long.toString(rs.getLong("seed")),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)
                ),
                symbol,
                simulationTradeDate
        );
    }

    private List<AutoMarketRegimeHistoryResponse.Modifier> loadModifiers(
            String symbol,
            LocalDate simulationTradeDate
    ) {
        return jdbcTemplate.query(
                """
                select regime_phase,
                       modifier_window_start_at,
                       price_pressure,
                       asset_preference_pressure,
                       volatility_pressure,
                       liquidity_pressure,
                       execution_aggression_pressure,
                       seed,
                       created_at,
                       updated_at
                  from stock_order_book_regime_modifier
                 where symbol = ?
                   and simulation_trade_date = ?
                 order by modifier_window_start_at asc
                """,
                (rs, rowNum) -> new AutoMarketRegimeHistoryResponse.Modifier(
                        rs.getString("regime_phase"),
                        rs.getObject("modifier_window_start_at", LocalDateTime.class),
                        rs.getInt("price_pressure"),
                        rs.getInt("asset_preference_pressure"),
                        rs.getInt("volatility_pressure"),
                        rs.getInt("liquidity_pressure"),
                        rs.getInt("execution_aggression_pressure"),
                        Long.toString(rs.getLong("seed")),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)
                ),
                symbol,
                simulationTradeDate
        );
    }
}
