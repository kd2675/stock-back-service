package stock.back.service.trading.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PortfolioSnapshotResponse(
        LocalDate snapshotDate,
        BigDecimal totalAsset,
        BigDecimal cashBalance,
        BigDecimal pendingSubscriptionAsset,
        BigDecimal marketValue,
        BigDecimal netContribution,
        BigDecimal totalProfit,
        BigDecimal returnRate,
        String returnRateStatus
) {
}
