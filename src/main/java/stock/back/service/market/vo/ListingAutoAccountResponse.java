package stock.back.service.market.vo;

import stock.back.service.database.entity.ListingAutoPosition;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ListingAutoAccountResponse(
        String symbol,
        String userKey,
        String displayName,
        boolean enabled,
        ListingAutoPosition positionSide,
        Long accountId,
        BigDecimal cashBalance,
        long holdingQuantity,
        long reservedQuantity,
        long availableQuantity,
        BigDecimal averagePrice,
        BigDecimal currentPrice,
        BigDecimal marketValue,
        int maxOrderQuantity,
        int orderTtlSeconds,
        int priceOffsetTicks,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
