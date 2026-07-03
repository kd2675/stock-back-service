package stock.back.service.market.vo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record OrderBookInstrumentTradingRulesRequest(
        @NotNull @Positive @DecimalMax("100.00") BigDecimal priceLimitRate
) {
}
