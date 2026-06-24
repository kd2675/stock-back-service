package stock.back.service.market.vo;

import jakarta.validation.constraints.NotNull;
import stock.back.service.database.entity.StockCorporateActionType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CorporateActionRequest(
        @NotNull StockCorporateActionType actionType,
        Long shareQuantity,
        BigDecimal issuePrice,
        Integer splitFrom,
        Integer splitTo,
        LocalDate exRightsDate,
        LocalDate paymentDate,
        LocalDate listingDate,
        BigDecimal dividendAmount,
        String description
) {
}
