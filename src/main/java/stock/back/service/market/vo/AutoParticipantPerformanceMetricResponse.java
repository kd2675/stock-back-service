package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AutoParticipantPerformanceMetricResponse(
        long accountCount,
        long eligibleAccountCount,
        long undefinedAccountCount,
        BigDecimal totalAsset,
        BigDecimal netContribution,
        BigDecimal totalProfit,
        BigDecimal aggregateReturnRate,
        BigDecimal medianAccountReturnRate,
        long profitableAccountCount,
        BigDecimal profitableAccountRate
) {
}
