package stock.back.service.market.vo;

import stock.back.service.database.entity.MarketSessionStatus;

public record MarketStatusUpdateRequest(
        Boolean enabled,
        MarketSessionStatus marketStatus
) {
}
