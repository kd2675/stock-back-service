package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminRecentCashFlowResponse(
        Long id,
        Long accountId,
        String userKey,
        String flowType,
        BigDecimal amount,
        String reason,
        String createdBy,
        LocalDateTime createdAt
) {
}
