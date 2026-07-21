package stock.back.service.market.vo;

import stock.back.service.database.entity.ListingAutoPosition;
import stock.back.service.database.entity.ListingAutoOperationMode;
import stock.back.service.database.entity.ListingAutoStrategyProfile;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ListingAutoAccountResponse(
        String symbol,
        String userKey,
        String displayName,
        boolean enabled,
        ListingAutoPosition positionSide,
        ListingAutoOperationMode operationMode,
        ListingAutoStrategyProfile strategyProfile,
        long issuedShares,
        long initialInventoryQuantity,
        BigDecimal initialIssuePrice,
        BigDecimal initialInventoryCost,
        Long accountId,
        BigDecimal cashBalance,
        long holdingQuantity,
        long reservedQuantity,
        long availableQuantity,
        BigDecimal averagePrice,
        BigDecimal currentPrice,
        BigDecimal marketValue,
        BigDecimal reservedBuyCash,
        BigDecimal totalEquity,
        BigDecimal netProfit,
        BigDecimal returnRate,
        int maxOrderQuantity,
        int orderTtlSeconds,
        int priceOffsetTicks,
        int targetSpreadTicks,
        int inventorySkewTicks,
        BigDecimal minimumProfitRate,
        BigDecimal aggressiveUnwindThreshold,
        BigDecimal aggressiveOrderRatio,
        long targetBuyQuantity,
        long targetSellQuantity,
        long targetHoldingQuantity,
        long inventoryBandQuantity,
        long openBuyQuantity,
        long openSellQuantity,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
