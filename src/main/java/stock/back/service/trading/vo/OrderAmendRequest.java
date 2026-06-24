package stock.back.service.trading.vo;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record OrderAmendRequest(
        @Positive Long quantity,
        BigDecimal limitPrice
) {
}
