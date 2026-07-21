package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import web.common.core.simulation.SimulationMarketSession;

public record SimulationClockResponse(
        LocalDate simulationDate,
        LocalDateTime simulationDateTime,
        LocalDateTime simulationDayStart,
        SimulationMarketSession marketSession,
        LocalTime marketOpenTime,
        LocalTime marketCloseTime,
        LocalTime preOpenTransformTime,
        LocalTime autoMarketPreparationTime,
        LocalDate activeBusinessDate,
        LocalDate preparingBusinessDate,
        String postClosePhase,
        String postCloseStatus,
        boolean postCloseProcessingCompleted,
        boolean marketOpenReady,
        List<SimulationClockJumpAction> availableJumpActions,
        int realSecondsPerSimulationDay,
        boolean running,
        boolean stale,
        long accumulatedRealSeconds,
        LocalDateTime lastStartedAt,
        LocalDateTime lastHeartbeatAt
) {
}
