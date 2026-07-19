package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AutoMarketDailyRegimeResponse(
        String symbol,
        LocalDate simulationTradeDate,
        String regimePhase,
        String sourceRegimePhase,
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
    public AutoMarketDailyRegimeResponse {
        sourceRegimePhase = sourceRegimePhase == null || sourceRegimePhase.isBlank()
                ? regimePhase
                : sourceRegimePhase;
    }

    public AutoMarketDailyRegimeResponse(
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
        this(
                symbol,
                simulationTradeDate,
                regimePhase,
                regimePhase,
                pricePressure,
                assetPreferencePressure,
                volatilityPressure,
                liquidityPressure,
                executionAggressionPressure,
                seed,
                currentModifier,
                createdAt,
                updatedAt
        );
    }
}
