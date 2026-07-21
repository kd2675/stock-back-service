package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AdminInvestorFlowSummaryResponse(
        LocalDate simulationTradeDate,
        long totalBuyQuantity,
        long totalSellQuantity,
        long totalParticipationQuantity,
        List<AdminInvestorCategoryFlowResponse> categories,
        LocalDateTime sourceUpdatedAt
) {
}
