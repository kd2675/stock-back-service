package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AutoMarketRegimeHistoryResponse(
        String symbol,
        LocalDate simulationTradeDate,
        LocalDate currentSimulationTradeDate,
        int dailyApplicationCount,
        int preparedRegimeSlotCount,
        List<DailyRegime> dailyRegimes,
        List<Modifier> modifiers
) {
    public AutoMarketRegimeHistoryResponse {
        dailyApplicationCount = Math.clamp(dailyApplicationCount, 0, 4);
        preparedRegimeSlotCount = Math.clamp(preparedRegimeSlotCount, 0, 4);
        dailyRegimes = dailyRegimes == null ? List.of() : List.copyOf(dailyRegimes);
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
    }

    public record DailyRegime(
            String regimePhase,
            String sourceRegimePhase,
            int pricePressure,
            int assetPreferencePressure,
            int volatilityPressure,
            int liquidityPressure,
            int executionAggressionPressure,
            String seed,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public DailyRegime {
            sourceRegimePhase = sourceRegimePhase == null || sourceRegimePhase.isBlank()
                    ? regimePhase
                    : sourceRegimePhase;
        }
    }

    public record Modifier(
            String regimePhase,
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
}
