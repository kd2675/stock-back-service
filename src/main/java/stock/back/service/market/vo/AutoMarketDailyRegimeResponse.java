package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AutoMarketDailyRegimeResponse(
        String symbol,
        LocalDate simulationTradeDate,
        String regimePhase,
        String sourceRegimePhase,
        int dailyApplicationCount,
        int preparedRegimeSlotCount,
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
        dailyApplicationCount = Math.clamp(dailyApplicationCount, 0, 4);
        preparedRegimeSlotCount = Math.clamp(preparedRegimeSlotCount, 0, 4);
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
                0,
                0,
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

    public AutoMarketDailyRegimeResponse withCurrentModifier(AutoMarketRegimeModifierResponse modifier) {
        return new AutoMarketDailyRegimeResponse(
                symbol,
                simulationTradeDate,
                regimePhase,
                sourceRegimePhase,
                dailyApplicationCount,
                preparedRegimeSlotCount,
                pricePressure,
                assetPreferencePressure,
                volatilityPressure,
                liquidityPressure,
                executionAggressionPressure,
                seed,
                modifier,
                createdAt,
                updatedAt
        );
    }
}
