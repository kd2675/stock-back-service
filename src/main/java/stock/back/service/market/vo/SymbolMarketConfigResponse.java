package stock.back.service.market.vo;

import stock.back.service.database.entity.MarketSessionStatus;

public record SymbolMarketConfigResponse(
        String symbol,
        boolean enabled,
        MarketSessionStatus marketStatus
) {
}
