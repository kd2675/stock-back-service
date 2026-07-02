package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SimulationClockResponse(
        LocalDate simulationDate,
        LocalDateTime simulationDateTime,
        LocalDateTime simulationDayStart,
        int realSecondsPerSimulationDay,
        boolean running,
        boolean stale,
        long accumulatedRealSeconds,
        LocalDateTime lastStartedAt,
        LocalDateTime lastHeartbeatAt
) {
}
