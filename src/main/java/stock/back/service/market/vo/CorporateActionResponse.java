package stock.back.service.market.vo;

import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CorporateActionResponse(
        Long id,
        String symbol,
        StockCorporateActionType actionType,
        Long shareQuantity,
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
        LocalDateTime appliedAt,
        LocalDateTime paidAt,
        LocalDateTime listedAt,
        Integer splitFrom,
        Integer splitTo,
        String description,
        LocalDateTime createdAt
) {
}
