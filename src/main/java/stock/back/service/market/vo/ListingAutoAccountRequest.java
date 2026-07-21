package stock.back.service.market.vo;

import stock.back.service.database.entity.ListingAutoPosition;
import stock.back.service.database.entity.ListingAutoOperationMode;
import stock.back.service.database.entity.ListingAutoStrategyProfile;

import java.math.BigDecimal;

public record ListingAutoAccountRequest(
        String displayName,
        Boolean enabled,
        ListingAutoPosition positionSide,
        ListingAutoOperationMode operationMode,
        ListingAutoStrategyProfile strategyProfile,
        Integer maxOrderQuantity,
        Integer orderTtlSeconds,
        Integer priceOffsetTicks,
        Integer targetSpreadTicks,
        Integer inventorySkewTicks,
        BigDecimal minimumProfitRate,
        BigDecimal aggressiveUnwindThreshold,
        BigDecimal aggressiveOrderRatio,
        Long targetBuyQuantity,
        Long targetSellQuantity,
        Long targetHoldingQuantity,
        Long inventoryBandQuantity
) {
}
