package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AutoParticipantProfileOverviewResponse(
        String profileType,
        long totalCount,
        long enabledCount,
        long disabledCount,
        long accountCount,
        BigDecimal availableCash,
        BigDecimal reservedBuyCash,
        BigDecimal holdingMarketValue,
        BigDecimal estimatedTotalAsset,
        BigDecimal netCashFlow,
        BigDecimal totalProfit,
        BigDecimal returnRate,
        long holdingCount,
        long totalHoldingQuantity,
        long reservedSellQuantity,
        long openOrderCount,
        long openBuyOrderCount,
        long openSellOrderCount,
        long openBuyQuantity,
        long openSellQuantity,
        long todayExecutionCount,
        long todayBuyQuantity,
        long todaySellQuantity,
        BigDecimal todayGrossAmount,
        long strategyCount,
        long enabledStrategyCount,
        LocalDateTime lastOrderAt,
        LocalDateTime lastExecutionAt,
        List<AutoParticipantProfileSymbolHoldingResponse> symbolHoldings
) {
    public AutoParticipantProfileOverviewResponse withSymbolHoldings(List<AutoParticipantProfileSymbolHoldingResponse> nextSymbolHoldings) {
        return new AutoParticipantProfileOverviewResponse(
                profileType,
                totalCount,
                enabledCount,
                disabledCount,
                accountCount,
                availableCash,
                reservedBuyCash,
                holdingMarketValue,
                estimatedTotalAsset,
                netCashFlow,
                totalProfit,
                returnRate,
                holdingCount,
                totalHoldingQuantity,
                reservedSellQuantity,
                openOrderCount,
                openBuyOrderCount,
                openSellOrderCount,
                openBuyQuantity,
                openSellQuantity,
                todayExecutionCount,
                todayBuyQuantity,
                todaySellQuantity,
                todayGrossAmount,
                strategyCount,
                enabledStrategyCount,
                lastOrderAt,
                lastExecutionAt,
                nextSymbolHoldings
        );
    }
}
