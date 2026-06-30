package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.OrderBookCandleResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderBookCandleQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;

    @Transactional(readOnly = true)
    public List<OrderBookCandleResponse> getOrderBookCandles(String symbol, String interval) {
        String normalizedSymbol = requireEnabledOrderBookSymbol(symbol);
        CandleInterval candleInterval = CandleInterval.parse(interval);
        LocalDateTime lastBucketStart = candleInterval.floor(LocalDateTime.now());
        LocalDateTime firstBucketStart = candleInterval.minus(lastBucketStart, candleInterval.limit() - 1);
        LocalDateTime endExclusive = candleInterval.next(lastBucketStart);
        String executionBucket = candleInterval.bucketExpression("executed_at");
        String sql = """
                select bucket_start,
                       max(case when open_rank = 1 then price end) as open_price,
                       max(price) as high_price,
                       min(price) as low_price,
                       max(case when close_rank = 1 then price end) as close_price,
                       sum(quantity) as volume,
                       sum(gross_amount) as turnover,
                       count(*) as execution_count
                from (
                    select %s as bucket_start,
                           price,
                           quantity,
                           gross_amount,
                           row_number() over(partition by %s order by executed_at asc, id asc) as open_rank,
                           row_number() over(partition by %s order by executed_at desc, id desc) as close_rank
                      from stock_execution
                     where symbol = ?
                       and source = 'INTERNAL_ORDER_BOOK'
                       and side = 'BUY'
                       and executed_at >= ?
                       and executed_at < ?
                ) ranked_execution
                group by bucket_start
                order by bucket_start asc
                """.formatted(executionBucket, executionBucket, executionBucket);
        Map<LocalDateTime, ExecutionCandleRow> executionCandlesByBucket = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ExecutionCandleRow(
                        rs.getTimestamp("bucket_start").toLocalDateTime(),
                        rs.getBigDecimal("open_price"),
                        rs.getBigDecimal("high_price"),
                        rs.getBigDecimal("low_price"),
                        rs.getBigDecimal("close_price"),
                        rs.getLong("volume"),
                        nonNullDecimal(rs.getBigDecimal("turnover")),
                        rs.getLong("execution_count")
                ),
                normalizedSymbol,
                firstBucketStart,
                endExclusive
        ).stream().collect(Collectors.toMap(
                ExecutionCandleRow::bucketStart,
                Function.identity(),
                (left, right) -> left
        ));
        BigDecimal lastClose = findPreviousOrderBookExecutionPrice(normalizedSymbol, firstBucketStart)
                .or(() -> stockPriceRepository.findById(normalizedSymbol).map(StockPrice::getCurrentPrice))
                .orElse(null);
        List<OrderBookCandleResponse> candles = new ArrayList<>(candleInterval.limit());
        LocalDateTime bucketStart = firstBucketStart;
        for (int index = 0; index < candleInterval.limit(); index++) {
            ExecutionCandleRow row = executionCandlesByBucket.get(bucketStart);
            if (row != null) {
                candles.add(row.toResponse(normalizedSymbol, candleInterval.value()));
                lastClose = row.closePrice();
            } else if (lastClose != null) {
                candles.add(new OrderBookCandleResponse(
                        normalizedSymbol,
                        candleInterval.value(),
                        bucketStart,
                        lastClose,
                        lastClose,
                        lastClose,
                        lastClose,
                        0L,
                        BigDecimal.ZERO,
                        0L,
                        false
                ));
            }
            bucketStart = candleInterval.next(bucketStart);
        }
        return candles;
    }

    private Optional<BigDecimal> findPreviousOrderBookExecutionPrice(String symbol, LocalDateTime before) {
        String sql = """
                select price
                  from stock_execution
                 where symbol = ?
                   and source = 'INTERNAL_ORDER_BOOK'
                   and side = 'BUY'
                   and executed_at < ?
                 order by executed_at desc, id desc
                 limit 1
                """;
        return Optional.ofNullable(jdbcTemplate.query(
                sql,
                rs -> rs.next() ? rs.getBigDecimal("price") : null,
                symbol,
                before
        ));
    }

    private String requireEnabledOrderBookSymbol(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue(normalizedSymbol)) {
            throw StockException.notFound("Unknown stock symbol: " + normalizedSymbol);
        }
        return normalizedSymbol;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal nonNullDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ExecutionCandleRow(
            LocalDateTime bucketStart,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            long volume,
            BigDecimal turnover,
            long executionCount
    ) {

        private OrderBookCandleResponse toResponse(String symbol, String interval) {
            return new OrderBookCandleResponse(
                    symbol,
                    interval,
                    bucketStart,
                    openPrice,
                    highPrice,
                    lowPrice,
                    closePrice,
                    volume,
                    turnover,
                    executionCount,
                    true
            );
        }
    }

    private enum CandleInterval {
        ONE_MINUTE("1M", 120),
        FIVE_MINUTES("5M", 120),
        FIFTEEN_MINUTES("15M", 120),
        ONE_HOUR("1H", 120),
        DAY("1D", 120),
        WEEK("1W", 120);

        private final String value;
        private final int limit;

        CandleInterval(String value, int limit) {
            this.value = value;
            this.limit = limit;
        }

        static CandleInterval parse(String value) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "1M", "1MIN", "1MINUTE" -> ONE_MINUTE;
                case "5M", "5MIN", "5MINUTE" -> FIVE_MINUTES;
                case "15M", "15MIN", "15MINUTE" -> FIFTEEN_MINUTES;
                case "1H", "H", "HOUR", "1HOUR" -> ONE_HOUR;
                case "1D", "D", "DAY" -> DAY;
                case "1W", "W", "WEEK" -> WEEK;
                default -> throw StockException.badRequest("Unknown candle interval: " + value);
            };
        }

        String bucketExpression(String column) {
            return switch (this) {
                case ONE_MINUTE -> "timestamp(date(" + column + "), maketime(hour(" + column + "), minute(" + column + "), 0))";
                case FIVE_MINUTES -> "timestamp(date(" + column + "), maketime(hour(" + column + "), floor(minute(" + column + ") / 5) * 5, 0))";
                case FIFTEEN_MINUTES -> "timestamp(date(" + column + "), maketime(hour(" + column + "), floor(minute(" + column + ") / 15) * 15, 0))";
                case ONE_HOUR -> "timestamp(date(" + column + "), maketime(hour(" + column + "), 0, 0))";
                case DAY -> "date(" + column + ")";
                case WEEK -> "date_sub(date(" + column + "), interval weekday(" + column + ") day)";
            };
        }

        LocalDateTime floor(LocalDateTime value) {
            return switch (this) {
                case ONE_MINUTE -> value.withSecond(0).withNano(0);
                case FIVE_MINUTES -> value.withMinute((value.getMinute() / 5) * 5).withSecond(0).withNano(0);
                case FIFTEEN_MINUTES -> value.withMinute((value.getMinute() / 15) * 15).withSecond(0).withNano(0);
                case ONE_HOUR -> value.withMinute(0).withSecond(0).withNano(0);
                case DAY -> value.toLocalDate().atStartOfDay();
                case WEEK -> value.toLocalDate().minusDays(value.getDayOfWeek().getValue() - 1L).atStartOfDay();
            };
        }

        LocalDateTime minus(LocalDateTime value, int intervals) {
            return switch (this) {
                case ONE_MINUTE -> value.minusMinutes(intervals);
                case FIVE_MINUTES -> value.minusMinutes(intervals * 5L);
                case FIFTEEN_MINUTES -> value.minusMinutes(intervals * 15L);
                case ONE_HOUR -> value.minusHours(intervals);
                case DAY -> value.minusDays(intervals);
                case WEEK -> value.minusWeeks(intervals);
            };
        }

        LocalDateTime next(LocalDateTime value) {
            return switch (this) {
                case ONE_MINUTE -> value.plusMinutes(1);
                case FIVE_MINUTES -> value.plusMinutes(5);
                case FIFTEEN_MINUTES -> value.plusMinutes(15);
                case ONE_HOUR -> value.plusHours(1);
                case DAY -> value.plusDays(1);
                case WEEK -> value.plusWeeks(1);
            };
        }

        String value() {
            return value;
        }

        int limit() {
            return limit;
        }
    }
}
