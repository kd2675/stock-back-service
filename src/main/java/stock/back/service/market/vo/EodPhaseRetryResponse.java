package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record EodPhaseRetryResponse(
        long cycleId,
        LocalDate businessDate,
        String phase,
        String previousStatus,
        String status,
        int attemptCount,
        String requestedBy,
        LocalDateTime requestedAt
) {
}
