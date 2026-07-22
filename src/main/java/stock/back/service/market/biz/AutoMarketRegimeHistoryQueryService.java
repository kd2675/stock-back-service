package stock.back.service.market.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoMarketRegimeHistoryRangeResponse;
import stock.back.service.market.vo.AutoMarketRegimeHistoryResponse;

@Service
@RequiredArgsConstructor
public class AutoMarketRegimeHistoryQueryService {

    private static final int RANGE_DAYS = 7;
    private static final int EXPECTED_DAILY_REGIME_COUNT = 4;
    private static final int EXPECTED_MODIFIER_COUNT = 24;

    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final SimulationClockService simulationClockService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public AutoMarketRegimeHistoryResponse getHistory(String symbol, LocalDate requestedTradeDate) {
        String normalizedSymbol = validateSymbol(symbol);

        LocalDate currentSimulationTradeDate = simulationClockService.currentDate();
        LocalDate simulationTradeDate = requestedTradeDate == null
                ? currentSimulationTradeDate
                : requestedTradeDate;
        List<AutoMarketRegimeHistoryResponse.DailyRegime> dailyRegimes = loadDailyRegimeRange(
                normalizedSymbol,
                simulationTradeDate,
                simulationTradeDate
        ).stream().map(DatedDailyRegime::dailyRegime).toList();
        List<AutoMarketRegimeHistoryResponse.Modifier> modifiers = loadModifierRange(
                normalizedSymbol,
                simulationTradeDate,
                simulationTradeDate
        ).stream().map(DatedModifier::modifier).toList();
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

    @Transactional(readOnly = true)
    public AutoMarketRegimeHistoryRangeResponse getHistoryRange(String symbol, LocalDate requestedEndDate) {
        String normalizedSymbol = validateSymbol(symbol);
        LocalDateTime currentSimulationDateTime = simulationClockService.currentMarketDateTime();
        LocalDate currentSimulationTradeDate = currentSimulationDateTime.toLocalDate();
        LocalDate rangeEndDate = requestedEndDate == null ? currentSimulationTradeDate : requestedEndDate;
        if (rangeEndDate.isAfter(currentSimulationTradeDate)) {
            throw StockException.badRequest("History range end date cannot be after the current simulation date");
        }
        LocalDate rangeStartDate = rangeEndDate.minusDays(RANGE_DAYS - 1L);

        Map<LocalDate, List<AutoMarketRegimeHistoryResponse.DailyRegime>> dailyRegimesByDate = new HashMap<>();
        loadDailyRegimeRange(normalizedSymbol, rangeStartDate, rangeEndDate).forEach(row ->
                dailyRegimesByDate.computeIfAbsent(row.simulationTradeDate(), ignored -> new ArrayList<>())
                        .add(row.dailyRegime())
        );
        Map<LocalDate, List<AutoMarketRegimeHistoryResponse.Modifier>> modifiersByDate = new HashMap<>();
        loadModifierRange(normalizedSymbol, rangeStartDate, rangeEndDate).forEach(row ->
                modifiersByDate.computeIfAbsent(row.simulationTradeDate(), ignored -> new ArrayList<>())
                        .add(row.modifier())
        );

        List<AutoMarketRegimeHistoryRangeResponse.Day> days = new ArrayList<>(RANGE_DAYS);
        for (LocalDate tradeDate = rangeStartDate; !tradeDate.isAfter(rangeEndDate); tradeDate = tradeDate.plusDays(1)) {
            List<AutoMarketRegimeHistoryResponse.DailyRegime> dailyRegimes = dailyRegimesByDate.getOrDefault(
                    tradeDate,
                    List.of()
            );
            List<AutoMarketRegimeHistoryResponse.Modifier> modifiers = modifiersByDate.getOrDefault(
                    tradeDate,
                    List.of()
            );
            int dailyApplicationCount = (int) dailyRegimes.stream()
                    .filter(regime -> regime.regimePhase().equals(regime.sourceRegimePhase()))
                    .count();
            days.add(new AutoMarketRegimeHistoryRangeResponse.Day(
                    tradeDate,
                    dailyApplicationCount,
                    dailyRegimes.size(),
                    EXPECTED_MODIFIER_COUNT,
                    modifiers.size(),
                    resolveSourceStatus(dailyRegimes.size(), modifiers.size()),
                    dailyRegimes,
                    modifiers
            ));
        }

        return new AutoMarketRegimeHistoryRangeResponse(
                normalizedSymbol,
                rangeStartDate,
                rangeEndDate,
                currentSimulationDateTime,
                days
        );
    }

    private String validateSymbol(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        return normalizedSymbol;
    }

    private AutoMarketRegimeHistoryRangeResponse.SourceStatus resolveSourceStatus(
            int dailyRegimeCount,
            int modifierCount
    ) {
        if (dailyRegimeCount == 0 && modifierCount == 0) {
            return AutoMarketRegimeHistoryRangeResponse.SourceStatus.MISSING;
        }
        if (dailyRegimeCount == EXPECTED_DAILY_REGIME_COUNT && modifierCount == EXPECTED_MODIFIER_COUNT) {
            return AutoMarketRegimeHistoryRangeResponse.SourceStatus.COMPLETE;
        }
        return AutoMarketRegimeHistoryRangeResponse.SourceStatus.PARTIAL;
    }

    private List<DatedDailyRegime> loadDailyRegimeRange(
            String symbol,
            LocalDate rangeStartDate,
            LocalDate rangeEndDate
    ) {
        return jdbcTemplate.query(
                """
                select simulation_trade_date,
                       regime_phase,
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
                   and simulation_trade_date between ? and ?
                 order by simulation_trade_date asc,
                          case regime_phase
                              when 'SLOT_0600' then 1
                              when 'SLOT_0900' then 2
                              when 'SLOT_1200' then 3
                              when 'SLOT_1500' then 4
                              else 5
                          end
                """,
                (rs, rowNum) -> new DatedDailyRegime(
                        rs.getObject("simulation_trade_date", LocalDate.class),
                        new AutoMarketRegimeHistoryResponse.DailyRegime(
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
                        )
                ),
                symbol,
                rangeStartDate,
                rangeEndDate
        );
    }

    private List<DatedModifier> loadModifierRange(
            String symbol,
            LocalDate rangeStartDate,
            LocalDate rangeEndDate
    ) {
        return jdbcTemplate.query(
                """
                select simulation_trade_date,
                       regime_phase,
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
                   and simulation_trade_date between ? and ?
                 order by simulation_trade_date asc,
                          modifier_window_start_at asc
                """,
                (rs, rowNum) -> new DatedModifier(
                        rs.getObject("simulation_trade_date", LocalDate.class),
                        new AutoMarketRegimeHistoryResponse.Modifier(
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
                        )
                ),
                symbol,
                rangeStartDate,
                rangeEndDate
        );
    }

    record DatedDailyRegime(
            LocalDate simulationTradeDate,
            AutoMarketRegimeHistoryResponse.DailyRegime dailyRegime
    ) {
    }

    record DatedModifier(
            LocalDate simulationTradeDate,
            AutoMarketRegimeHistoryResponse.Modifier modifier
    ) {
    }
}
