package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record EodOperationsOverviewResponse(
        LocalDateTime generatedAt,
        BusinessState businessState,
        MarketState marketState,
        Cycle cycle,
        CycleMetrics metrics,
        List<ReadinessCheck> readinessChecks,
        PhaseAttempt latestAttempt,
        Signal latestSignal
) {

    public record BusinessState(
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            LocalDate rawSimulationDate,
            LocalDateTime rawSimulationDateTime,
            long version,
            LocalDateTime updatedAt
    ) {
    }

    public record MarketState(
            int enabledSymbolCount,
            int openSymbolCount,
            boolean orderEntryOpen
    ) {
    }

    public record Cycle(
            long id,
            LocalDate businessDate,
            String cycleKind,
            String skipReason,
            String phase,
            String status,
            int phaseRevision,
            int attemptCount,
            Long closeRunId,
            LocalDateTime settlementEligibleAt,
            String ownerId,
            LocalDateTime leaseUntil,
            LocalDateTime nextRetryAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            String lastErrorCode,
            String lastErrorMessage,
            String buildVersion,
            String schemaVersion,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String closeRunStatus,
            LocalDateTime closedAt,
            LocalDateTime closeRunCompletedAt
    ) {
    }

    public record CycleMetrics(
            long capturedOpenOrderCount,
            long cancelledOrderCount,
            BigDecimal releasedBuyCash,
            long releasedSellQuantity,
            long settlementTargetAccountCount,
            long accountSnapshotCount,
            long holdingSnapshotCount,
            long priceSnapshotCount,
            long openOrderSummaryCount,
            long reconciliationMismatchCount,
            long settledAccountCount,
            long settlementMissingAccountCount,
            LocalDateTime updatedAt
    ) {
    }

    public record ReadinessCheck(
            String checkCode,
            int displayOrder,
            String status,
            long failureCount,
            String message,
            LocalDateTime checkedAt
    ) {
    }

    public record PhaseAttempt(
            long id,
            String phase,
            int attemptNo,
            Long batchJobExecutionId,
            String ownerId,
            String status,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            String errorCode,
            String errorMessage,
            String buildVersion,
            String schemaVersion
    ) {
    }

    public record Signal(
            long id,
            String signalType,
            String jobName,
            String executionMode,
            String status,
            LocalDateTime requestedAt,
            LocalDateTime eligibleAt,
            LocalDateTime nextAttemptAt,
            int attemptCount,
            int maxAttempts,
            Integer processedCount,
            String message,
            String errorMessage,
            LocalDateTime completedAt
    ) {
    }
}
