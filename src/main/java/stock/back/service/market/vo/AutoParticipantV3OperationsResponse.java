package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AutoParticipantV3OperationsResponse(
        LocalDate simulationTradeDate,
        List<PolicyRevision> policies,
        DailySummary dailySummary,
        List<DailyAccountState> accountStates,
        long incompleteLiquidationPlanCount
) {

    public record PolicyRevision(
            long policyVersion,
            String status,
            LocalDate effectiveTradeDate,
            boolean runtimeEnabled,
            String policyJson,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime activatedAt,
            LocalDateTime retiredAt,
            String runtimeChangeReason,
            String runtimeChangedBy,
            LocalDateTime runtimeChangedAt
    ) {
    }

    public record DailySummary(
            long accountCount,
            long offlineAccountCount,
            long submittedOrderCount,
            BigDecimal submittedNotional,
            long observedExecutionCount,
            BigDecimal observedExecutionNotional,
            long observedCancelCount,
            BigDecimal averageFatigueScore
    ) {
    }

    public record DailyAccountState(
            long accountId,
            String userKey,
            String profileType,
            long policyVersion,
            String activityState,
            String activitySession,
            long eventSequence,
            BigDecimal fatigueScore,
            long submittedOrderCount,
            BigDecimal submittedNotional,
            long observedExecutionCount,
            BigDecimal observedExecutionNotional,
            long observedCancelCount,
            String lastResultReason,
            String lastHoldReason,
            LocalDateTime nextAttentionAt,
            LocalDateTime nextGuardAt,
            LocalDateTime nextRunAt,
            LocalDateTime updatedAt
    ) {
    }
}
