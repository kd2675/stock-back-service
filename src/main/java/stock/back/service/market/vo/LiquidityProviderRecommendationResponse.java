package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.util.List;

public record LiquidityProviderRecommendationResponse(
        int recommendedProviderCount,
        long currentProviderCount,
        long recommendedRemainingCount,
        BigDecimal recommendedReferenceDailyVolumeRate,
        BigDecimal minReferenceDailyVolumeRate,
        BigDecimal maxReferenceDailyVolumeRate,
        BigDecimal recommendedSeedInventoryRate,
        BigDecimal minSeedInventoryRate,
        BigDecimal maxSeedInventoryRate,
        BigDecimal recommendedInitialCashMultiplier,
        List<Symbol> symbols
) {

    public LiquidityProviderRecommendationResponse {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }

    public record Symbol(
            String symbol,
            long tradableShares,
            BigDecimal currentPrice,
            boolean marketEnabled,
            String marketStatus,
            boolean existingMandate,
            Long recommendedSourceAccountId,
            long sourceAvailableQuantity,
            long recommendedReferenceDailyVolume,
            BigDecimal recommendedReferenceDailyVolumeRate,
            int referenceVolumeHistoryDays,
            String referenceVolumeSource,
            long recommendedSeedInventoryQuantity,
            BigDecimal recommendedInitialCash,
            boolean creationEligible,
            String eligibilityReason
    ) {
    }
}
