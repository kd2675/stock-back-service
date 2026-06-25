package stock.back.service.market.vo;

import java.time.LocalDateTime;

public record StockBatchJobRunResponse(
        String job,
        String status,
        String executionMode,
        int processedCount,
        String message,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}
