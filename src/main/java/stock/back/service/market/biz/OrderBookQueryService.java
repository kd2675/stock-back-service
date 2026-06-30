package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.vo.OrderBookLevelResponse;
import stock.back.service.market.vo.OrderBookRecentExecutionResponse;
import stock.back.service.market.vo.OrderBookResponse;
import stock.back.service.market.vo.OrderBookTradeSummaryResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class OrderBookQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockOrderRepository stockOrderRepository;

    @Transactional(readOnly = true)
    public OrderBookResponse getOrderBook(String symbol) {
        String normalizedSymbol = requireEnabledOrderBookSymbol(symbol);
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        var page = PageRequest.of(0, 10);
        List<OrderBookLevelResponse> bids = stockOrderRepository
                .findBidLevels(normalizedSymbol, MarketType.ORDER_BOOK, OrderSide.BUY, OrderType.LIMIT, openStatuses, page)
                .stream()
                .map(this::toOrderBookLevelResponse)
                .toList();
        List<OrderBookLevelResponse> asks = stockOrderRepository
                .findAskLevels(normalizedSymbol, MarketType.ORDER_BOOK, OrderSide.SELL, OrderType.LIMIT, openStatuses, page)
                .stream()
                .map(this::toOrderBookLevelResponse)
                .toList();
        return new OrderBookResponse(normalizedSymbol, bids, asks);
    }

    @Transactional(readOnly = true)
    public OrderBookTradeSummaryResponse getOrderBookTradeSummary(String symbol) {
        String normalizedSymbol = requireEnabledOrderBookSymbol(symbol);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        String sql = """
                select
                  coalesce(count(*), 0) as today_execution_count,
                  coalesce(sum(quantity), 0) as today_volume,
                  coalesce(sum(gross_amount), 0) as today_turnover,
                  coalesce(sum(case when side = 'BUY' then quantity else 0 end), 0) as buy_volume,
                  coalesce(sum(case when side = 'SELL' then quantity else 0 end), 0) as sell_volume,
                  coalesce(sum(case when side = 'BUY' then gross_amount else 0 end), 0) as buy_turnover,
                  coalesce(sum(case when side = 'SELL' then gross_amount else 0 end), 0) as sell_turnover,
                  min(price) as low_price,
                  max(price) as high_price,
                  (select e.price
                     from stock_execution e
                    where e.symbol = ?
                      and e.source = 'INTERNAL_ORDER_BOOK'
                    order by e.executed_at desc, e.id desc
                    limit 1) as last_price,
                  (select e.executed_at
                     from stock_execution e
                    where e.symbol = ?
                      and e.source = 'INTERNAL_ORDER_BOOK'
                    order by e.executed_at desc, e.id desc
                    limit 1) as last_executed_at
                from stock_execution
                where symbol = ?
                  and source = 'INTERNAL_ORDER_BOOK'
                  and executed_at >= ?
                """;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
            long todayVolume = rs.getLong("today_volume");
            long sellVolume = rs.getLong("sell_volume");
            BigDecimal todayTurnover = nullToZero(rs.getBigDecimal("today_turnover"));
            BigDecimal vwap = todayVolume <= 0
                    ? BigDecimal.ZERO
                    : todayTurnover.divide(BigDecimal.valueOf(todayVolume), 4, RoundingMode.HALF_UP);
            BigDecimal executionStrength = sellVolume <= 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(rs.getLong("buy_volume"))
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(sellVolume), 2, RoundingMode.HALF_UP);
            return new OrderBookTradeSummaryResponse(
                    normalizedSymbol,
                    rs.getLong("today_execution_count"),
                    todayVolume,
                    todayTurnover,
                    vwap,
                    nullToZero(rs.getBigDecimal("high_price")),
                    nullToZero(rs.getBigDecimal("low_price")),
                    rs.getLong("buy_volume"),
                    sellVolume,
                    nullToZero(rs.getBigDecimal("buy_turnover")),
                    nullToZero(rs.getBigDecimal("sell_turnover")),
                    executionStrength,
                    nullToZero(rs.getBigDecimal("last_price")),
                    rs.getTimestamp("last_executed_at") == null ? null : rs.getTimestamp("last_executed_at").toLocalDateTime()
            );
        }, normalizedSymbol, normalizedSymbol, normalizedSymbol, todayStart);
    }

    @Transactional(readOnly = true)
    public List<OrderBookRecentExecutionResponse> getRecentOrderBookExecutions(String symbol) {
        String normalizedSymbol = requireEnabledOrderBookSymbol(symbol);
        String sql = """
                select id,
                       symbol,
                       side,
                       quantity,
                       price,
                       gross_amount,
                       executed_at
                  from stock_execution
                 where symbol = ?
                   and source = 'INTERNAL_ORDER_BOOK'
                 order by executed_at desc, id desc
                 limit 30
                """;
        List<RecentExecutionRow> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new RecentExecutionRow(
                rs.getLong("id"),
                rs.getString("symbol"),
                OrderSide.valueOf(rs.getString("side")),
                rs.getLong("quantity"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("gross_amount"),
                rs.getTimestamp("executed_at").toLocalDateTime()
        ), normalizedSymbol);
        return IntStream.range(0, rows.size())
                .mapToObj(index -> {
                    RecentExecutionRow row = rows.get(index);
                    BigDecimal previousPrice = index + 1 < rows.size() ? rows.get(index + 1).price() : null;
                    BigDecimal priceChange = previousPrice == null ? BigDecimal.ZERO : row.price().subtract(previousPrice);
                    return new OrderBookRecentExecutionResponse(
                            row.id(),
                            row.symbol(),
                            row.side(),
                            row.quantity(),
                            row.price(),
                            row.grossAmount(),
                            priceChange,
                            row.executedAt()
                    );
                })
                .toList();
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

    private OrderBookLevelResponse toOrderBookLevelResponse(StockOrderRepository.OrderBookLevelView level) {
        return new OrderBookLevelResponse(
                level.getPrice(),
                level.getQuantity() == null ? 0L : level.getQuantity(),
                level.getOrderCount() == null ? 0L : level.getOrderCount()
        );
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record RecentExecutionRow(
            Long id,
            String symbol,
            OrderSide side,
            long quantity,
            BigDecimal price,
            BigDecimal grossAmount,
            LocalDateTime executedAt
    ) {
    }
}
