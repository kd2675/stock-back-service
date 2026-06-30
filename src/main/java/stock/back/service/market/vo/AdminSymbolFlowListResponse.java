package stock.back.service.market.vo;

import java.util.List;

public record AdminSymbolFlowListResponse(
        long totalCount,
        List<AdminSymbolFlowResponse> symbolFlows
) {
}
