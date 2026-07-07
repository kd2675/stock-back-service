package stock.back.service.market.vo;

import java.util.List;

public record AdminSymbolFlowListResponse(
        long totalCount,
        List<AdminSymbolFlowResponse> symbolFlows,
        List<AdminSymbolFlowDailyCumulativeResponse> dailyCumulativeFlows
) {
    public AdminSymbolFlowListResponse(long totalCount, List<AdminSymbolFlowResponse> symbolFlows) {
        this(totalCount, symbolFlows, List.of());
    }
}
