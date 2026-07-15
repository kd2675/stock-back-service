package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AdminFundFlowSummaryResponse(
        long activeAccountCount,
        BigDecimal totalCashBalance,
        BigDecimal totalReservedBuyCash,
        BigDecimal totalHoldingMarketValue,
        long totalHoldingQuantity,
        long totalReservedSellQuantity,
        long totalAvailableHoldingQuantity,
        long holdingPositionCount,
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
        long executionCount
) {
}
