package stock.back.service.market.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record OrderBookInstrumentRequest(
        @NotBlank String symbol,
        @NotBlank String name,
        String market,
        @NotNull @Positive BigDecimal initialPrice,
        @NotNull @Positive Long issuedShares,
        @Positive BigDecimal priceLimitRate,
        ListingAutoAccountRequest listingAutoAccount,
        InitialIssueAllocationRequest initialIssueAllocation
) {

    public OrderBookInstrumentRequest(
            String symbol,
            String name,
            String market,
            BigDecimal initialPrice,
            Long issuedShares,
            BigDecimal priceLimitRate,
            ListingAutoAccountRequest listingAutoAccount
    ) {
        this(
                symbol,
                name,
                market,
                initialPrice,
                issuedShares,
                priceLimitRate,
                listingAutoAccount,
                null
        );
    }
}
