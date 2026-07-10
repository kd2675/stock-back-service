package stock.back.service.market.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CorporateActionSubscriptionRequest(
        @NotNull @Positive Long shareQuantity
) {
}
