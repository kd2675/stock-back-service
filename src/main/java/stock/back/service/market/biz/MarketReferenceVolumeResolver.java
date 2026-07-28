package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Resolves a risk reference volume from completed market history.
 *
 * <p>The reference is an exposure denominator, not a volume target. A fixed
 * float percentage materially understates capacity when the simulated market
 * turns over faster than a conventional cash market, so completed 20-day ADV
 * is preferred and a float-based value is used only before history exists.</p>
 */
@Component
public class MarketReferenceVolumeResolver {

    public static final BigDecimal FALLBACK_FLOAT_RATE = new BigDecimal("0.030000");
    public static final BigDecimal MIN_FLOAT_RATE = new BigDecimal("0.005000");
    public static final BigDecimal MAX_FLOAT_RATE = new BigDecimal("2.000000");

    private static final int HISTORY_DAY_LIMIT = 20;

    private final JdbcClient jdbcClient;

    public MarketReferenceVolumeResolver(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Map<String, Resolution> resolve(Collection<SymbolFloat> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> tradableSharesBySymbol = symbols.stream()
                .collect(Collectors.toMap(
                        SymbolFloat::symbol,
                        SymbolFloat::tradableShares,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        tradableSharesBySymbol.forEach((symbol, tradableShares) -> {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("Reference-volume symbol must be non-blank");
            }
            if (tradableShares <= 0L) {
                throw new IllegalArgumentException(
                        "Tradable shares must be positive for " + symbol
                );
            }
        });

        Map<String, List<Long>> completedDailyVolumes = completedDailyVolumes(
                tradableSharesBySymbol.keySet()
        );
        Map<String, Resolution> resolutions = new LinkedHashMap<>();
        tradableSharesBySymbol.forEach((symbol, tradableShares) -> {
            List<Long> history = completedDailyVolumes.getOrDefault(symbol, List.of());
            BigDecimal rawVolume = history.isEmpty()
                    ? BigDecimal.valueOf(tradableShares).multiply(FALLBACK_FLOAT_RATE)
                    : history.stream()
                            .map(BigDecimal::valueOf)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(
                                    BigDecimal.valueOf(history.size()),
                                    0,
                                    RoundingMode.HALF_UP
                            );
            long minimum = scaledQuantity(tradableShares, MIN_FLOAT_RATE);
            long maximum = scaledQuantity(tradableShares, MAX_FLOAT_RATE);
            long resolved = Math.clamp(
                    positiveLong(rawVolume),
                    minimum,
                    maximum
            );
            BigDecimal rate = BigDecimal.valueOf(resolved)
                    .divide(
                            BigDecimal.valueOf(tradableShares),
                            6,
                            RoundingMode.HALF_UP
                    );
            resolutions.put(
                    symbol,
                    new Resolution(
                            resolved,
                            rate,
                            history.size(),
                            history.isEmpty()
                                    ? "FLOAT_FALLBACK"
                                    : "COMPLETED_20_DAY_ADV"
                    )
            );
        });
        return Map.copyOf(resolutions);
    }

    private Map<String, List<Long>> completedDailyVolumes(Set<String> symbols) {
        List<DailyVolume> rows = jdbcClient.sql(
                        """
                        select latest.symbol,
                               latest.simulation_trade_date,
                               latest.buy_quantity
                          from (
                                select snapshot.symbol,
                                       snapshot.simulation_trade_date,
                                       snapshot.buy_quantity,
                                       row_number() over (
                                           partition by snapshot.symbol,
                                                        snapshot.simulation_trade_date
                                           order by snapshot.close_run_id desc,
                                                    snapshot.id desc
                                       ) as date_rank
                                  from stock_order_book_daily_snapshot snapshot
                                  join stock_market_close_run close_run
                                    on close_run.id = snapshot.close_run_id
                                   and close_run.symbol is null
                                   and close_run.status = 'COMPLETED'
                                 where snapshot.symbol in (:symbols)
                               ) latest
                         where latest.date_rank = 1
                         order by latest.symbol,
                                  latest.simulation_trade_date desc
                        """
                )
                .param("symbols", symbols)
                .query((rs, rowNum) -> new DailyVolume(
                        rs.getString("symbol"),
                        Math.max(0L, rs.getLong("buy_quantity"))
                ))
                .list();
        Map<String, List<Long>> volumes = new LinkedHashMap<>();
        for (DailyVolume row : rows) {
            List<Long> symbolVolumes = volumes.computeIfAbsent(
                    row.symbol(),
                    ignored -> new ArrayList<>(HISTORY_DAY_LIMIT)
            );
            if (symbolVolumes.size() < HISTORY_DAY_LIMIT) {
                symbolVolumes.add(row.volume());
            }
        }
        return volumes;
    }

    private long scaledQuantity(long base, BigDecimal rate) {
        return positiveLong(
                BigDecimal.valueOf(base)
                        .multiply(rate)
                        .setScale(0, RoundingMode.CEILING)
        );
    }

    private long positiveLong(BigDecimal value) {
        return Math.max(
                1L,
                value.setScale(0, RoundingMode.HALF_UP).longValueExact()
        );
    }

    public record SymbolFloat(String symbol, long tradableShares) {
    }

    public record Resolution(
            long referenceDailyVolume,
            BigDecimal referenceDailyVolumeRate,
            int completedHistoryDays,
            String source
    ) {
    }

    private record DailyVolume(
            String symbol,
            long volume
    ) {
    }
}
