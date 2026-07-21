package stock.back.service.market.vo;

import java.time.LocalDate;
import java.util.List;

public record AdminInvestorFlowHistoryResponse(
        LocalDate rangeStart,
        LocalDate rangeEnd,
        List<AdminInvestorFlowSummaryResponse> dailyFlows
) {
}
