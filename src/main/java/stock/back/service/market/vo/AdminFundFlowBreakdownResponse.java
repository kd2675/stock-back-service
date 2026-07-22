package stock.back.service.market.vo;

import java.time.LocalDateTime;
import java.util.List;

public record AdminFundFlowBreakdownResponse(
        AdminFundFlowScope scope,
        LocalDateTime generatedAt,
        AdminFundFlowSummaryResponse total,
        List<AdminParticipantFundFlowResponse> categories
) {
}
