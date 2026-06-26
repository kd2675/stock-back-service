package stock.back.service.market.vo;

import java.time.LocalDateTime;
import java.util.List;

public record AdminFlowOverviewResponse(
        AdminFundFlowSummaryResponse fundFlow,
        AdminOrderFlowSummaryResponse orderFlow,
        AdminCorporateActionFlowSummaryResponse corporateActionFlow,
        List<AdminSymbolFlowResponse> symbolFlows,
        List<AdminRecentCashFlowResponse> recentCashFlows,
        LocalDateTime generatedAt
) {
}
