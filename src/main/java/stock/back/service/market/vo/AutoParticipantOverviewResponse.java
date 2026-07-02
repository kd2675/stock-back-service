package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AutoParticipantOverviewResponse(
        String userKey,
        String displayName,
        boolean enabled,
        String profileType,
        Long accountId,
        String accountStatus,
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
        List<AutoParticipantHoldingResponse> holdings,
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime withdrawnAt
) {
    public AutoParticipantOverviewResponse withHoldings(List<AutoParticipantHoldingResponse> nextHoldings) {
        return new AutoParticipantOverviewResponse(
                userKey,
                displayName,
                enabled,
                profileType,
                accountId,
                accountStatus,
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
                nextHoldings,
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
                createdAt,
                updatedAt,
                withdrawnAt
        );
    }
}
