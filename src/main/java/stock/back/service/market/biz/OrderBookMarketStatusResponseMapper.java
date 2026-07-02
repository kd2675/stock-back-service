package stock.back.service.market.biz;

import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.market.vo.OrderBookMarketStatusResponse;
import stock.back.service.market.vo.SymbolMarketConfigResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

final class OrderBookMarketStatusResponseMapper {

    private OrderBookMarketStatusResponseMapper() {
    }

    static OrderBookMarketStatusResponse toStatus(
            long configCount,
            long openConfigCount,
            long instrumentCount,
            long openOrderCount,
            long todayExecutionCount,
            List<SymbolMarketConfigResponse> configs
    ) {
        return new OrderBookMarketStatusResponse(
                openConfigCount > 0,
                configCount,
                openConfigCount,
                instrumentCount,
                openOrderCount,
                todayExecutionCount,
                configs
        );
    }

    static OrderBookMarketStatusResponse toSummaryStatus(ResultSet rs) throws SQLException {
        long configCount = rs.getLong("config_count");
        return toStatus(
                configCount,
                rs.getLong("open_config_count"),
                rs.getLong("instrument_count"),
                rs.getLong("open_order_count"),
                rs.getLong("today_execution_count"),
                List.of()
        );
    }

    static SymbolMarketConfigResponse toMarketConfig(StockOrderBookMarketConfig config) {
        return new SymbolMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                normalizeMarketSessionStatus(config.getMarketStatus())
        );
    }

    static boolean isOpen(SymbolMarketConfigResponse config) {
        return config.enabled() && config.marketStatus() == MarketSessionStatus.OPEN;
    }

    private static MarketSessionStatus normalizeMarketSessionStatus(MarketSessionStatus marketStatus) {
        return marketStatus == null ? MarketSessionStatus.OPEN : marketStatus;
    }
}
