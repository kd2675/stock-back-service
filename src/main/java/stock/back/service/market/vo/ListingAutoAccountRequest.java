package stock.back.service.market.vo;

import stock.back.service.database.entity.ListingAutoPosition;

public record ListingAutoAccountRequest(
        String displayName,
        Boolean enabled,
        ListingAutoPosition positionSide,
        Integer maxOrderQuantity,
        Integer orderTtlSeconds,
        Integer priceOffsetTicks
) {
}
