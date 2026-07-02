package stock.back.service.market.biz;

import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.vo.OrderBookLevelResponse;
import stock.back.service.market.vo.OrderBookRecentExecutionResponse;
import stock.back.service.market.vo.OrderBookTradeSummaryResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

final class OrderBookResponseMapper {

    private OrderBookResponseMapper() {
    }

    static OrderBookLevelResponse toLevel(StockOrderRepository.OrderBookLevelView level) {
        return new OrderBookLevelResponse(
                level.getPrice(),
                level.getQuantity() == null ? 0L : level.getQuantity(),
                level.getOrderCount() == null ? 0L : level.getOrderCount()
        );
    }

    static OrderBookTradeSummaryResponse toTradeSummary(String symbol, ResultSet rs) throws SQLException {
        long todayVolume = rs.getLong("today_volume");
        long buyVolume = rs.getLong("buy_volume");
        long sellVolume = rs.getLong("sell_volume");
        BigDecimal todayTurnover = MarketQuerySupport.zeroIfNull(rs.getBigDecimal("today_turnover"));
        return new OrderBookTradeSummaryResponse(
                symbol,
                rs.getLong("today_execution_count"),
                todayVolume,
                todayTurnover,
                volumeWeightedAveragePrice(todayTurnover, todayVolume),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("high_price")),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("low_price")),
                buyVolume,
                sellVolume,
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("buy_turnover")),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("sell_turnover")),
                executionStrength(buyVolume, sellVolume),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("last_price")),
                MarketQuerySupport.toDateTime(rs.getTimestamp("last_executed_at"))
        );
    }

    static RecentExecutionRow toRecentExecutionRow(ResultSet rs) throws SQLException {
        return new RecentExecutionRow(
                rs.getLong("id"),
                rs.getString("symbol"),
                OrderSide.valueOf(rs.getString("side")),
                rs.getLong("quantity"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("gross_amount"),
                rs.getTimestamp("executed_at").toLocalDateTime()
        );
    }

    static List<OrderBookRecentExecutionResponse> toRecentExecutions(List<RecentExecutionRow> rows) {
        return IntStream.range(0, rows.size())
                .mapToObj(index -> toRecentExecution(rows, index))
                .toList();
    }

    private static OrderBookRecentExecutionResponse toRecentExecution(List<RecentExecutionRow> rows, int index) {
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
    }

    private static BigDecimal volumeWeightedAveragePrice(BigDecimal turnover, long volume) {
        if (volume <= 0) {
            return BigDecimal.ZERO;
        }
        return turnover.divide(BigDecimal.valueOf(volume), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal executionStrength(long buyVolume, long sellVolume) {
        if (sellVolume <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(buyVolume)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(sellVolume), 2, RoundingMode.HALF_UP);
    }

    record RecentExecutionRow(
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
