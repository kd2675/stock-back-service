package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;

record AutoParticipantAssetSummary(
        BigDecimal estimatedTotalAsset,
        BigDecimal netContribution,
        BigDecimal totalProfit,
        BigDecimal returnRate,
        PortfolioReturnRateStatus returnRateStatus
) {

    static AutoParticipantAssetSummary from(
            BigDecimal availableCash,
            BigDecimal reservedBuyCash,
            BigDecimal holdingMarketValue,
            BigDecimal netContribution
    ) {
        BigDecimal estimatedTotalAsset = availableCash.add(reservedBuyCash).add(holdingMarketValue);
        BigDecimal totalProfit = estimatedTotalAsset.subtract(netContribution);
        PortfolioReturnRateStatus returnRateStatus = PortfolioReturnRateStatus.from(netContribution);
        BigDecimal returnRate = null;
        if (returnRateStatus == PortfolioReturnRateStatus.DEFINED) {
            returnRate = totalProfit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(netContribution, 8, RoundingMode.HALF_UP);
        }
        return new AutoParticipantAssetSummary(
                estimatedTotalAsset,
                netContribution,
                totalProfit,
                returnRate,
                returnRateStatus
        );
    }
}
