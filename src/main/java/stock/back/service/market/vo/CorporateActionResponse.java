package stock.back.service.market.vo;

import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CorporateActionResponse(
        Long id,
        String symbol,
        StockCorporateActionType actionType,
        Long shareQuantity,
        Long subscribedShareQuantity,
        Long remainingShareQuantity,
        BigDecimal issuePrice,
        BigDecimal dividendAmount,
        StockCorporateActionStatus status,
        BigDecimal basePrice,
        BigDecimal theoreticalExRightsPrice,
        LocalDate exRightsDate,
        LocalDate paymentDate,
        LocalDate listingDate,
        LocalDate delistingDate,
        String delistingTreatment,
        StockCapitalIncreaseOfferingType offeringType,
        LocalDate subscriptionStartDate,
        LocalDate subscriptionEndDate,
        LocalDateTime appliedAt,
        LocalDateTime paidAt,
        LocalDateTime listedAt,
        Integer splitFrom,
        Integer splitTo,
        String description,
        LocalDateTime createdAt
) {
}
