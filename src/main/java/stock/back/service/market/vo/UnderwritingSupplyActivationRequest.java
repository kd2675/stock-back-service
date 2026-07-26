package stock.back.service.market.vo;

import java.math.BigDecimal;

public record UnderwritingSupplyActivationRequest(
        BigDecimal supplyRate,
        Integer durationDays,
        String changeReason
) {
}
