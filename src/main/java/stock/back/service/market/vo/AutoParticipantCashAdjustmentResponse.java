package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AutoParticipantCashAdjustmentResponse(
        String userKey,
        String adjustmentType,
        BigDecimal amount,
        BigDecimal cashBalance,
        LocalDateTime updatedAt
) {
}
