package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AutoMarketRegimeHistoryRangeResponse(
        String symbol,
        LocalDate rangeStartDate,
        LocalDate rangeEndDate,
        LocalDateTime currentSimulationDateTime,
        List<Day> days
) {
    public AutoMarketRegimeHistoryRangeResponse {
        days = days == null ? List.of() : List.copyOf(days);
    }

    public record Day(
            LocalDate simulationTradeDate,
            int dailyApplicationCount,
            int preparedRegimeSlotCount,
            int expectedWindowCount,
            int availableWindowCount,
            SourceStatus sourceStatus,
            List<AutoMarketRegimeHistoryResponse.DailyRegime> dailyRegimes,
            List<AutoMarketRegimeHistoryResponse.Modifier> modifiers
    ) {
        public Day {
            dailyApplicationCount = Math.clamp(dailyApplicationCount, 0, 4);
            preparedRegimeSlotCount = Math.clamp(preparedRegimeSlotCount, 0, 4);
            expectedWindowCount = Math.max(0, expectedWindowCount);
            availableWindowCount = Math.max(0, availableWindowCount);
            sourceStatus = sourceStatus == null ? SourceStatus.MISSING : sourceStatus;
            dailyRegimes = dailyRegimes == null ? List.of() : List.copyOf(dailyRegimes);
            modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        }
    }

    public enum SourceStatus {
        COMPLETE,
        PARTIAL,
        MISSING
    }
}
