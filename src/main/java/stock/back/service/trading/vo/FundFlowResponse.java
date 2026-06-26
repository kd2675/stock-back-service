package stock.back.service.trading.vo;

import java.math.BigDecimal;
import java.util.List;

public record FundFlowResponse(
        BigDecimal cashBalance,
        BigDecimal reservedBuyCash,
        BigDecimal marketValue,
        BigDecimal totalAsset,
        BigDecimal externalDepositAmount,
        BigDecimal externalWithdrawAmount,
        BigDecimal netExternalCashFlow,
        BigDecimal dividendIncomeAmount,
        BigDecimal buyNetAmount,
        BigDecimal sellNetAmount,
        BigDecimal tradeNetCashFlow,
        BigDecimal totalFeeAmount,
        BigDecimal totalTaxAmount,
        BigDecimal realizedProfit,
        BigDecimal unrealizedProfit,
        BigDecimal totalProfit,
        long executionCount,
        List<AccountCashFlowResponse> recentCashFlows
) {
}
