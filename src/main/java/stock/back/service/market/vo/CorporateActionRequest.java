package stock.back.service.market.vo;

import jakarta.validation.constraints.NotNull;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;
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
        LocalDate delistingDate,
        BigDecimal dividendAmount,
        StockCapitalIncreaseOfferingType offeringType,
        LocalDate subscriptionStartDate,
        LocalDate subscriptionEndDate,
        String description
) {
    public CorporateActionRequest(
            StockCorporateActionType actionType,
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
        this(
                actionType,
                shareQuantity,
                issuePrice,
                splitFrom,
                splitTo,
                exRightsDate,
                paymentDate,
                listingDate,
                null,
                dividendAmount,
                null,
                null,
                null,
                description
        );
    }

    public CorporateActionRequest(
            StockCorporateActionType actionType,
            Long shareQuantity,
            BigDecimal issuePrice,
            Integer splitFrom,
            Integer splitTo,
            LocalDate exRightsDate,
            LocalDate paymentDate,
            LocalDate listingDate,
            LocalDate delistingDate,
            BigDecimal dividendAmount,
            String description
    ) {
        this(
                actionType,
                shareQuantity,
                issuePrice,
                splitFrom,
                splitTo,
                exRightsDate,
                paymentDate,
                listingDate,
                delistingDate,
                dividendAmount,
                null,
                null,
                null,
                description
        );
    }
}
