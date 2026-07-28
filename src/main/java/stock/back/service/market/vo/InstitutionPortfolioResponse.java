package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record InstitutionPortfolioResponse(
        long portfolioId,
        String portfolioCode,
        String displayName,
        String investmentStyle,
        String executionMode,
        String status,
        long policyVersion,
        long participantId,
        String participantCode,
        String participantStatus,
        String participantSelfTradeGroupId,
        long accountId,
        String accountUserKey,
        String accountStatus,
        String accountSelfTradeGroupId,
        BigDecimal cashBalance,
        BigDecimal openBuyReservedCash,
        BigDecimal holdingMarketValue,
        BigDecimal totalAsset,
        BigDecimal currentStockAllocationRate,
        BigDecimal baseStockAllocationRate,
        BigDecimal minStockAllocationRate,
        BigDecimal maxStockAllocationRate,
        BigDecimal primaryRegimeWeight,
        BigDecimal assetPreferenceSensitivity,
        BigDecimal volatilitySensitivity,
        BigDecimal entryThresholdRate,
        BigDecimal exitThresholdRate,
        BigDecimal dailyTurnoverLimitRate,
        BigDecimal maxDecisionTurnoverRate,
        int decisionIntervalMinutes,
        LocalDateTime nextDecisionAt,
        Long latestDecisionRunId,
        LocalDateTime latestDecisionSlot,
        String latestDecisionStatus,
        Long latestDeterministicSeed,
        String latestDecisionError,
        LocalDateTime latestDecisionCompletedAt,
        LocalDate budgetTradeDate,
        long dailyPlannedBuyQuantity,
        long dailyPlannedSellQuantity,
        BigDecimal dailyPlannedBuyAmount,
        BigDecimal dailyPlannedSellAmount,
        BigDecimal dailySubmittedBuyAmount,
        BigDecimal dailySubmittedSellAmount,
        long institutionalOpenOrderCount,
        int completedDecisionTradingDays,
        int recentDecisionFailureCount,
        InstitutionPortfolioScheduledPolicyResponse scheduledPolicy,
        List<InstitutionSymbolMandateResponse> mandates
) {

    public InstitutionPortfolioResponse {
        mandates = mandates == null ? List.of() : List.copyOf(mandates);
    }
}
