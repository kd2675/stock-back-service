package stock.back.service.trading.vo;

import java.math.BigDecimal;

public record ProfitSummaryResponse(
        BigDecimal realizedProfit,
        BigDecimal unrealizedProfit,
        BigDecimal totalProfit,
        BigDecimal totalFeeAmount,
        BigDecimal totalTaxAmount,
        BigDecimal buyGrossAmount,
        BigDecimal sellGrossAmount,
        BigDecimal buyNetAmount,
        BigDecimal sellNetAmount,
        BigDecimal netCashFlow,
        long executionCount
) {
}
