package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AdminInvestorCategoryFlowResponse(
        String category,
        long buyQuantity,
        long sellQuantity,
        long netQuantity,
        long participationQuantity,
        BigDecimal buyAmount,
        BigDecimal sellAmount,
        BigDecimal netCashFlow,
        BigDecimal buyShareRate,
        BigDecimal sellShareRate,
        BigDecimal executionShareRate
) {
}
