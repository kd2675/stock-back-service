package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import web.common.core.simulation.SimulationMarketSession;

public record SimulationClockResponse(
        LocalDate simulationDate,
        LocalDateTime simulationDateTime,
        LocalDateTime simulationDayStart,
        SimulationMarketSession marketSession,
        LocalTime marketOpenTime,
        LocalTime marketCloseTime,
        boolean postCloseProcessingCompleted,
        int realSecondsPerSimulationDay,
        boolean running,
        boolean stale,
        long accumulatedRealSeconds,
        LocalDateTime lastStartedAt,
        LocalDateTime lastHeartbeatAt
) {
}
