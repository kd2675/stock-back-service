package stock.back.service.market.vo;

import java.time.LocalDateTime;

public record BatchJobRuntimeStatusResponse(
        String jobName,
        boolean schedulerConfigured,
        boolean runtimeEnabled,
        boolean effectiveEnabled,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
