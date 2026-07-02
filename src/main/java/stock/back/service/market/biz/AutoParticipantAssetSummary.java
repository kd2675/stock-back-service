package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;

record AutoParticipantAssetSummary(
        BigDecimal estimatedTotalAsset,
        BigDecimal totalProfit,
        BigDecimal returnRate
) {

    static AutoParticipantAssetSummary from(
            BigDecimal availableCash,
            BigDecimal reservedBuyCash,
            BigDecimal holdingMarketValue,
            BigDecimal netCashFlow
    ) {
        BigDecimal estimatedTotalAsset = availableCash.add(reservedBuyCash).add(holdingMarketValue);
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal returnRate = BigDecimal.ZERO;
        if (netCashFlow.compareTo(BigDecimal.ZERO) > 0) {
            totalProfit = estimatedTotalAsset.subtract(netCashFlow);
            returnRate = totalProfit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(netCashFlow, 4, RoundingMode.HALF_UP);
        }
        return new AutoParticipantAssetSummary(estimatedTotalAsset, totalProfit, returnRate);
    }
}
