package stock.back.service.trading.vo;

import jakarta.validation.constraints.Positive;

public record OrderCancelRequest(
        @Positive Long quantity
) {
}
