package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AutoMarketDailyRegimeResponse(
        String symbol,
        LocalDate simulationTradeDate,
        String regimePhase,
        int pricePressure,
        int assetPreferencePressure,
        int volatilityPressure,
        int liquidityPressure,
        int executionAggressionPressure,
        String seed,
        AutoMarketRegimeModifierResponse currentModifier,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
