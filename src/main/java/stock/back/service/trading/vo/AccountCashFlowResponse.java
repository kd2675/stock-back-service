package stock.back.service.trading.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountCashFlowResponse(
        Long id,
        String flowType,
        BigDecimal amount,
        String reason,
        String createdBy,
        LocalDateTime createdAt
) {
}
