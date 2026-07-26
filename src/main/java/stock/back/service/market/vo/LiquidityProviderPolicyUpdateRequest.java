package stock.back.service.market.vo;

import java.math.BigDecimal;

public record LiquidityProviderPolicyUpdateRequest(
        Integer targetSpreadTicks,
        Integer maxSpreadTicks,
        Long maxOrderQuantity,
        Long referenceDailyVolume,
        BigDecimal targetOpenParticipationRate,
        BigDecimal maxOpenParticipationRate,
        BigDecimal maxSingleOrderParticipationRate,
        Integer externalDepthLevels,
        BigDecimal maxExternalDepthParticipationRate,
        BigDecimal dailyExecutionParticipationRate,
        BigDecimal dailySubmissionMultiplier,
        Long targetInventoryQuantity,
        Long inventoryBandQuantity,
        Integer inventorySkewTicks,
        Integer volatilitySpreadMaxTicks,
        Integer priceRegimeMaxSkewTicks,
        Integer minimumQuoteLifetimeSeconds,
        Integer repriceThresholdTicks,
        Integer orderTtlSeconds,
        Integer quoteIntervalSeconds,
        BigDecimal dailyLossLimitAmount,
        String changeReason
) {
}
