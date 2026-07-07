package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AdminSymbolFlowDailyCumulativeResponse(
        LocalDate simulationTradeDate,
        LocalDateTime rangeStart,
        LocalDateTime rangeEnd,
        long totalCount,
        List<AdminSymbolFlowResponse> symbolFlows
) {
}
