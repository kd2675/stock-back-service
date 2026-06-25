package stock.back.service.trading.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountCashAdjustmentResponse(
        Long accountId,
        String userKey,
        String adjustmentType,
        BigDecimal amount,
        BigDecimal cashBalance,
        LocalDateTime updatedAt
) {
}
