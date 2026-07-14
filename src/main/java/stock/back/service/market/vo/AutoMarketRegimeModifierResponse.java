package stock.back.service.market.vo;

import java.time.LocalDateTime;

public record AutoMarketRegimeModifierResponse(
        LocalDateTime modifierWindowStartAt,
        int pricePressure,
        int assetPreferencePressure,
        int volatilityPressure,
        int liquidityPressure,
        int executionAggressionPressure,
        String seed,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
