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
    public static AdminFundFlowSummaryResponse zero() {
        return new AdminFundFlowSummaryResponse(
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0
        );
    }

    public AdminFundFlowSummaryResponse plus(AdminFundFlowSummaryResponse other) {
        return new AdminFundFlowSummaryResponse(
                activeAccountCount + other.activeAccountCount,
                totalCashBalance.add(other.totalCashBalance),
                totalReservedBuyCash.add(other.totalReservedBuyCash),
                totalHoldingMarketValue.add(other.totalHoldingMarketValue),
                totalHoldingQuantity + other.totalHoldingQuantity,
                totalReservedSellQuantity + other.totalReservedSellQuantity,
                totalAvailableHoldingQuantity + other.totalAvailableHoldingQuantity,
                holdingPositionCount + other.holdingPositionCount,
                totalAsset.add(other.totalAsset),
                externalDepositAmount.add(other.externalDepositAmount),
                externalWithdrawAmount.add(other.externalWithdrawAmount),
                netExternalCashFlow.add(other.netExternalCashFlow),
                dividendIncomeAmount.add(other.dividendIncomeAmount),
                buyNetAmount.add(other.buyNetAmount),
                sellNetAmount.add(other.sellNetAmount),
                tradeNetCashFlow.add(other.tradeNetCashFlow),
                totalFeeAmount.add(other.totalFeeAmount),
                totalTaxAmount.add(other.totalTaxAmount),
                realizedProfit.add(other.realizedProfit),
                executionCount + other.executionCount
        );
    }
}
