package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AutoMarketDailyRegimeResponse(
        String symbol,
        LocalDate simulationTradeDate,
        String regimePhase,
        String priceDirection,
        String assetPreference,
        int directionIntensity,
        int volatilityLevel,
        int liquidityLevel,
        int executionAggressionLevel,
        String seed,
        AutoMarketRegimeModifierResponse currentModifier,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
