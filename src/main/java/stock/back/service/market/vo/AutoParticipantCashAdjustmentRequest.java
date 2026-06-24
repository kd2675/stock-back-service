package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AutoParticipantCashAdjustmentRequest(
        String adjustmentType,
        BigDecimal amount
) {
}
