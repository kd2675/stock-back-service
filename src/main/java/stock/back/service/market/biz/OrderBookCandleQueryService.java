package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.OrderBookCandleResponse;
import web.common.core.simulation.SimulationClockSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderBookCandleQueryService {

    private final JdbcClient jdbcClient;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final SimulationClockService simulationClockService;

    public OrderBookCandleQueryService(
            JdbcTemplate jdbcTemplate,
            StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository,
            StockPriceRepository stockPriceRepository,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.stockOrderBookInstrumentRepository = stockOrderBookInstrumentRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true)
    public List<OrderBookCandleResponse> getOrderBookCandles(String symbol, String interval) {
        String normalizedSymbol = requireEnabledOrderBookSymbol(symbol);
        OrderBookCandleInterval candleInterval = OrderBookCandleInterval.parse(interval);
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime lastBucketStart = candleInterval.floor(clock.simulationDateTime(), clock);
        LocalDateTime firstBucketStart = candleInterval.minus(lastBucketStart, candleInterval.limit() - 1, clock);
        LocalDateTime endExclusive = candleInterval.next(lastBucketStart, clock);
        long simulationBucketSeconds = candleInterval.usesSimulationClockAnchor()
                ? candleInterval.simulationBucketSeconds(clock)
                : 0;
        String executionBucket = candleInterval.bucketExpression("executed_at", simulationBucketSeconds);
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
        Map<LocalDateTime, ExecutionCandleRow> executionCandlesByBucket = jdbcClient.sql(sql)
                .params(queryParameters(candleInterval, lastBucketStart, normalizedSymbol, firstBucketStart, endExclusive))
                .query((rs, rowNum) -> new ExecutionCandleRow(
                        rs.getTimestamp("bucket_start").toLocalDateTime(),
                        rs.getBigDecimal("open_price"),
                        rs.getBigDecimal("high_price"),
                        rs.getBigDecimal("low_price"),
                        rs.getBigDecimal("close_price"),
                        rs.getLong("volume"),
                        MarketQuerySupport.zeroIfNull(rs.getBigDecimal("turnover")),
                        rs.getLong("execution_count")
                ))
                .list()
                .stream()
                .collect(Collectors.toMap(
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
            bucketStart = candleInterval.next(bucketStart, clock);
        }
        return candles;
    }

    private List<Object> queryParameters(
            OrderBookCandleInterval candleInterval,
            LocalDateTime bucketAnchor,
            String symbol,
            LocalDateTime firstBucketStart,
            LocalDateTime endExclusive
    ) {
        List<Object> parameters = new ArrayList<>();
        if (candleInterval.usesSimulationClockAnchor()) {
            addBucketAnchorParameters(parameters, bucketAnchor);
            addBucketAnchorParameters(parameters, bucketAnchor);
            addBucketAnchorParameters(parameters, bucketAnchor);
        }
        parameters.add(symbol);
        parameters.add(firstBucketStart);
        parameters.add(endExclusive);
        return parameters;
    }

    private void addBucketAnchorParameters(List<Object> parameters, LocalDateTime bucketAnchor) {
        parameters.add(bucketAnchor);
        parameters.add(bucketAnchor);
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
        return jdbcClient.sql(sql)
                .params(symbol, before)
                .query(BigDecimal.class)
                .optional();
    }

    private String requireEnabledOrderBookSymbol(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue(normalizedSymbol)) {
            throw StockException.notFound("Unknown stock symbol: " + normalizedSymbol);
        }
        return normalizedSymbol;
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

}
