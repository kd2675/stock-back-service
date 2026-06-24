package stock.back.service.market.vo;

import stock.back.service.database.entity.StockCorporateActionEntitlementStatus;
import stock.back.service.database.entity.StockCorporateActionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CorporateActionEntitlementResponse(
        Long id,
        Long accountId,
        Long actionId,
        String symbol,
        StockCorporateActionType actionType,
        long quantity,
        Long shareQuantity,
        BigDecimal cashAmount,
        StockCorporateActionEntitlementStatus status,
        LocalDateTime createdAt,
        LocalDateTime paidAt
) {
}
