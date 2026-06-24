package stock.back.service.market.vo;

import java.util.List;

public record AutoMarketStatusResponse(
        boolean enabled,
        long enabledParticipantCount,
        long openAutoOrderCount,
        long todayAutoExecutionCount,
        List<AutoMarketConfigResponse> configs,
        List<AutoParticipantResponse> participants,
        List<AutoParticipantSymbolConfigResponse> participantSymbolConfigs
) {
}
