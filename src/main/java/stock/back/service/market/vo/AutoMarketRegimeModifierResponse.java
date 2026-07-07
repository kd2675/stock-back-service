package stock.back.service.market.vo;

import java.time.LocalDateTime;

public record AutoMarketRegimeModifierResponse(
        LocalDateTime modifierWindowStartAt,
        int priceDirectionModifier,
        int assetPreferenceModifier,
        int directionIntensityModifier,
        int volatilityModifier,
        int liquidityModifier,
        int executionAggressionModifier,
        String seed,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
