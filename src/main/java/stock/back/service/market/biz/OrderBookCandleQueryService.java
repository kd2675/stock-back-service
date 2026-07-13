package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.ConnectionCallback;
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

@Service
public class OrderBookCandleQueryService {

    private final JdbcClient jdbcClient;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final SimulationClockService simulationClockService;
    private final String executionIndexHint;
    private final String integerCastType;

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
        boolean mysql = isMySql(jdbcTemplate);
        this.executionIndexHint = mysql ? "force index (idx_stock_execution_candle)" : "";
        this.integerCastType = mysql ? "signed" : "integer";
    }

    @Transactional(readOnly = true)
    public List<OrderBookCandleResponse> getOrderBookCandles(String symbol, String interval) {
        String normalizedSymbol = requireEnabledOrderBookSymbol(symbol);
        OrderBookCandleInterval candleInterval = OrderBookCandleInterval.parse(interval);
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime currentSimulationTime = clock.simulationDateTime();
        LocalDateTime lastBucketStart = candleInterval.floor(clock.simulationDateTime(), clock);
        LocalDateTime firstBucketStart = candleInterval.minus(lastBucketStart, candleInterval.limit() - 1, clock);
        LocalDateTime endExclusive = candleInterval.next(lastBucketStart, clock);
        long simulationBucketSeconds = candleInterval.usesSimulationClockAnchor()
                ? candleInterval.simulationBucketSeconds(clock)
                : 0;
        String executionBucket = candleInterval.bucketExpression("executed_at", simulationBucketSeconds);
        String sql = """
                select bucket_start,
                       max(price) as high_price,
                       min(price) as low_price,
                       sum(quantity) as volume,
                       sum(gross_amount) as turnover,
                       count(*) as execution_count
                  from (
                    select %s as bucket_start, price, quantity, gross_amount
                      from stock_execution %s
                     where symbol = ?
                       and source = 'INTERNAL_ORDER_BOOK'
                       and side = 'BUY'
                       and executed_at >= ?
                       and executed_at < ?
                       and executed_at <= ?
                  ) bucketed_execution
                 group by bucket_start
                 order by bucket_start asc
                """.formatted(executionBucket, executionIndexHint);
        List<ExecutionCandleAggregateRow> aggregateRows = jdbcClient.sql(sql)
                .params(queryParameters(candleInterval, lastBucketStart, normalizedSymbol, firstBucketStart, endExclusive, currentSimulationTime))
                .query((rs, rowNum) -> new ExecutionCandleAggregateRow(
                        rs.getTimestamp("bucket_start").toLocalDateTime(),
                        rs.getBigDecimal("high_price"),
                        rs.getBigDecimal("low_price"),
                        rs.getLong("volume"),
                        MarketQuerySupport.zeroIfNull(rs.getBigDecimal("turnover")),
                        rs.getLong("execution_count")
                ))
                .list();
        Map<Integer, ExecutionBoundaryPriceRow> boundaryPrices = findBoundaryPrices(
                normalizedSymbol,
                aggregateRows,
                candleInterval,
                clock,
                currentSimulationTime
        );
        Map<LocalDateTime, ExecutionCandleRow> executionCandlesByBucket = IntStream.range(0, aggregateRows.size())
                .mapToObj(index -> toExecutionCandleRow(aggregateRows.get(index), boundaryPrices.get(index)))
                .filter(Optional::isPresent)
                .map(Optional::get)
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
            LocalDateTime endExclusive,
            LocalDateTime currentSimulationTime
    ) {
        List<Object> parameters = new ArrayList<>();
        if (candleInterval.usesSimulationClockAnchor()) {
            addBucketAnchorParameters(parameters, bucketAnchor);
        }
        parameters.add(symbol);
        parameters.add(firstBucketStart);
        parameters.add(endExclusive);
        parameters.add(currentSimulationTime);
        return parameters;
    }

    private Map<Integer, ExecutionBoundaryPriceRow> findBoundaryPrices(
            String symbol,
            List<ExecutionCandleAggregateRow> aggregateRows,
            OrderBookCandleInterval candleInterval,
            SimulationClockSnapshot clock,
            LocalDateTime currentSimulationTime
    ) {
        if (aggregateRows.isEmpty()) {
            return Map.of();
        }
        String sql = IntStream.range(0, aggregateRows.size())
                .mapToObj(index -> """
                        select cast(:bucketIndex%d as %s) as bucket_index,
                               (
                                   select price
                                     from stock_execution %s
                                    where symbol = :symbol
                                      and source = 'INTERNAL_ORDER_BOOK'
                                      and side = 'BUY'
                                      and executed_at >= :bucketStart%d
                                      and executed_at < :bucketEnd%d
                                      and executed_at <= :currentSimulationTime
                                    order by executed_at asc, id asc
                                    limit 1
                               ) as open_price,
                               (
                                   select price
                                     from stock_execution %s
                                    where symbol = :symbol
                                      and source = 'INTERNAL_ORDER_BOOK'
                                      and side = 'BUY'
                                      and executed_at >= :bucketStart%d
                                      and executed_at < :bucketEnd%d
                                      and executed_at <= :currentSimulationTime
                                    order by executed_at desc, id desc
                                    limit 1
                               ) as close_price
                        """.formatted(
                        index,
                        integerCastType,
                        executionIndexHint,
                        index,
                        index,
                        executionIndexHint,
                        index,
                        index
                ))
                .collect(Collectors.joining("\nunion all\n"));
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("symbol", symbol)
                .param("currentSimulationTime", currentSimulationTime);
        for (int index = 0; index < aggregateRows.size(); index++) {
            LocalDateTime bucketStart = aggregateRows.get(index).bucketStart();
            statement = statement
                    .param("bucketIndex" + index, index)
                    .param("bucketStart" + index, bucketStart)
                    .param("bucketEnd" + index, candleInterval.next(bucketStart, clock));
        }
        return statement
                .query((rs, rowNum) -> new ExecutionBoundaryPriceRow(
                        rs.getInt("bucket_index"),
                        rs.getBigDecimal("open_price"),
                        rs.getBigDecimal("close_price")
                ))
                .list()
                .stream()
                .collect(Collectors.toMap(
                        ExecutionBoundaryPriceRow::bucketIndex,
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    private Optional<ExecutionCandleRow> toExecutionCandleRow(
            ExecutionCandleAggregateRow aggregate,
            ExecutionBoundaryPriceRow boundary
    ) {
        if (boundary == null || boundary.openPrice() == null || boundary.closePrice() == null) {
            return Optional.empty();
        }
        return Optional.of(new ExecutionCandleRow(
                aggregate.bucketStart(),
                boundary.openPrice(),
                aggregate.highPrice(),
                aggregate.lowPrice(),
                boundary.closePrice(),
                aggregate.volume(),
                aggregate.turnover(),
                aggregate.executionCount()
        ));
    }

    private boolean isMySql(JdbcTemplate jdbcTemplate) {
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
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

    private record ExecutionCandleAggregateRow(
            LocalDateTime bucketStart,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            long volume,
            BigDecimal turnover,
            long executionCount
    ) {
    }

    private record ExecutionBoundaryPriceRow(
            int bucketIndex,
            BigDecimal openPrice,
            BigDecimal closePrice
    ) {
    }

}
