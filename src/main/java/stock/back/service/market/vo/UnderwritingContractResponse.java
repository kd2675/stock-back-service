package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record UnderwritingContractResponse(
        long contractId,
        String contractCode,
        Long corporateActionId,
        String symbol,
        String instrumentName,
        long issuedShares,
        long instrumentTradableShares,
        long totalIssueQuantity,
        long tradableAllocationQuantity,
        long lockedAllocationQuantity,
        long externalAllocationQuantity,
        long underwrittenQuantity,
        BigDecimal tradableShareRate,
        BigDecimal issuePrice,
        String underwritingType,
        LocalDate stabilizationStartDate,
        LocalDate stabilizationEndDate,
        long stabilizationQuantityLimit,
        BigDecimal stabilizationAmountLimit,
        String status,
        long policyVersion,
        Account account,
        Supply supply,
        Reconciliation reconciliation,
        List<SecurityAllocationResponse> allocations,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public record Account(
            long participantId,
            String participantCode,
            String participantDisplayName,
            String participantType,
            String participantStatus,
            String participantSelfTradeGroupId,
            long accountId,
            String accountCode,
            String accountStatus,
            String participantCategory,
            String accountSelfTradeGroupId,
            String accountRole,
            String deskCode,
            String roleMappingStatus,
            LocalDate roleEffectiveFrom,
            LocalDate roleEffectiveTo,
            BigDecimal cashBalance,
            long holdingQuantity,
            long reservedSellQuantity,
            long availableSellQuantity,
            BigDecimal averagePrice,
            BigDecimal currentPrice,
            BigDecimal holdingMarketValue,
            long openUnderwritingOrderCount,
            long openUnderwritingOrderQuantity,
            long nonContractOpenOrderCount,
            long unmanagedHoldingCount
    ) {
    }

    public record Supply(
            BigDecimal configuredSupplyRate,
            long lifetimeSubmittedQuantity,
            BigDecimal lifetimeSubmittedAmount,
            long lifetimeExecutedQuantity,
            BigDecimal lifetimeExecutedAmount,
            long remainingSubmissionQuantity,
            BigDecimal remainingSubmissionAmount,
            long generatedOrderCount,
            long cancelledOrderCount,
            DailyState latestDailyState
    ) {
    }

    public record DailyState(
            LocalDate simulationTradeDate,
            long referenceDailyVolume,
            long submissionQuantityLimit,
            BigDecimal submissionAmountLimit,
            long submittedQuantity,
            BigDecimal submittedAmount,
            long generatedOrderCount,
            long cancelledOrderCount,
            BigDecimal lastOrderPrice,
            String stateStatus,
            String gateReason,
            long policyVersion,
            LocalDateTime updatedAt
    ) {
    }

    public record Reconciliation(
            long initialAllocationLedgerQuantity,
            long initialTradableLedgerQuantity,
            long initialLockedLedgerQuantity,
            long currentTotalHoldingQuantity,
            boolean contractQuantityBalanced,
            boolean instrumentQuantityCovered,
            boolean allocationLedgerMatched,
            boolean holdingSupplyMatched,
            boolean roleEligible,
            List<String> issues
    ) {
    }
}
