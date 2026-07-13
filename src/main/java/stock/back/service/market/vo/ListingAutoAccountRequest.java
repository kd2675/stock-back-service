package stock.back.service.market.vo;

import stock.back.service.database.entity.ListingAutoPosition;
import stock.back.service.database.entity.ListingAutoPriceDirection;

public record ListingAutoAccountRequest(
        String displayName,
        Boolean enabled,
        ListingAutoPosition positionSide,
        Integer maxOrderQuantity,
        Integer orderTtlSeconds,
        Integer priceOffsetTicks,
        Long targetBuyQuantity,
        Long targetSellQuantity,
        Long targetHoldingQuantity,
        Long inventoryBandQuantity,
        ListingAutoPriceDirection buyPriceOffsetDirection,
        ListingAutoPriceDirection sellPriceOffsetDirection
) {
    public ListingAutoAccountRequest(
            String displayName,
            Boolean enabled,
            ListingAutoPosition positionSide,
            Integer maxOrderQuantity,
            Integer orderTtlSeconds,
            Integer priceOffsetTicks,
            Long targetBuyQuantity,
            Long targetSellQuantity,
            Long targetHoldingQuantity,
            ListingAutoPriceDirection buyPriceOffsetDirection,
            ListingAutoPriceDirection sellPriceOffsetDirection
    ) {
        this(
                displayName,
                enabled,
                positionSide,
                maxOrderQuantity,
                orderTtlSeconds,
                priceOffsetTicks,
                targetBuyQuantity,
                targetSellQuantity,
                targetHoldingQuantity,
                0L,
                buyPriceOffsetDirection,
                sellPriceOffsetDirection
        );
    }

    public ListingAutoAccountRequest(
            String displayName,
            Boolean enabled,
            ListingAutoPosition positionSide,
            Integer maxOrderQuantity,
            Integer orderTtlSeconds,
            Integer priceOffsetTicks
    ) {
        this(
                displayName,
                enabled,
                positionSide,
                maxOrderQuantity,
                orderTtlSeconds,
                priceOffsetTicks,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
