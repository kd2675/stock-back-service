package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record LiquidityProviderMandateResponse(
        long mandateId,
        String mandateCode,
        String symbol,
        String executionMode,
        String status,
        LocalDate simulationTradeDate,
        LocalDate contractStartDate,
        LocalDate contractEndDate,
        LocalDateTime nextQuoteAt,
        long policyVersion,
        boolean legacyListingLiquidityEnabled,
        boolean roleEligible,
        String roleEligibilityIssue,
        Account account,
        Policy policy,
        DailyState dailyState,
        Transition transition
) {

    public record Account(
            long participantId,
            String participantCode,
            String participantType,
            String participantStatus,
            String participantSelfTradeGroupId,
            long accountId,
            String accountCode,
            String accountStatus,
            String participantCategory,
            String accountSelfTradeGroupId,
            String accountRole,
            String roleMappingStatus,
            LocalDate roleEffectiveFrom,
            LocalDate roleEffectiveTo,
            BigDecimal availableCash,
            long holdingQuantity,
            long reservedSellQuantity,
            long availableSellQuantity,
            BigDecimal averagePrice,
            BigDecimal currentPrice,
            BigDecimal holdingMarketValue,
            long nonLiquidityOpenOrderCount,
            long unmanagedHoldingCount
    ) {
    }

    public record Policy(
            int targetSpreadTicks,
            int maxSpreadTicks,
            long maxOrderQuantity,
            long referenceDailyVolume,
            BigDecimal targetOpenParticipationRate,
            BigDecimal maxOpenParticipationRate,
            BigDecimal maxSingleOrderParticipationRate,
            int externalDepthLevels,
            BigDecimal maxExternalDepthParticipationRate,
            BigDecimal dailyExecutionParticipationRate,
            BigDecimal dailySubmissionMultiplier,
            long targetInventoryQuantity,
            long inventoryBandQuantity,
            int inventorySkewTicks,
            BigDecimal primaryRegimeWeight,
            BigDecimal liquiditySizeSensitivity,
            int volatilitySpreadMaxTicks,
            int priceRegimeMaxSkewTicks,
            boolean passiveOnly,
            int minimumQuoteLifetimeSeconds,
            int repriceThresholdTicks,
            int orderTtlSeconds,
            int quoteIntervalSeconds,
            BigDecimal dailyLossLimitAmount
    ) {
    }

    public record DailyState(
            LocalDate simulationTradeDate,
            long referenceDailyVolume,
            long executionQuantityLimit,
            long submissionQuantityLimit,
            long submittedBuyQuantity,
            long submittedSellQuantity,
            BigDecimal submittedBuyAmount,
            BigDecimal submittedSellAmount,
            long cancelledBuyQuantity,
            long cancelledSellQuantity,
            long executedBuyQuantity,
            long executedSellQuantity,
            BigDecimal executedBuyAmount,
            BigDecimal executedSellAmount,
            BigDecimal realizedProfit,
            BigDecimal unrealizedProfit,
            BigDecimal openingNetAssetValue,
            BigDecimal currentNetAssetValue,
            BigDecimal riskProfit,
            long targetBuyOpenQuantity,
            long targetSellOpenQuantity,
            long lastOpenBuyQuantity,
            long lastOpenSellQuantity,
            long externalBuyDepthQuantity,
            long externalSellDepthQuantity,
            BigDecimal lastBidPrice,
            BigDecimal lastAskPrice,
            long lastInventoryQuantity,
            long lastProjectedInventoryQuantity,
            BigDecimal blendedPricePressure,
            BigDecimal blendedVolatilityPressure,
            BigDecimal blendedLiquidityPressure,
            String stateStatus,
            String gateReason,
            long quoteRunCount,
            boolean limitBreached,
            long policyVersion,
            long version,
            LocalDateTime updatedAt
    ) {
    }

    public record Transition(
            long transitionId,
            String transitionKey,
            String stage,
            long sourceAccountId,
            Long legacyAccountId,
            long referenceDailyVolume,
            long seedInventoryQuantity,
            BigDecimal seedCashAmount,
            LocalDate effectiveBusinessDate,
            LocalDateTime legacyDisabledAt,
            LocalDateTime activatedAt,
            String requestedBy,
            String changeReason,
            long policyVersion,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
