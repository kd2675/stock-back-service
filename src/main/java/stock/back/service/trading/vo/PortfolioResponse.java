package stock.back.service.trading.vo;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioResponse(
        AccountResponse account,
        BigDecimal marketValue,
        BigDecimal reservedBuyCash,
        BigDecimal totalAsset,
        BigDecimal netContribution,
        BigDecimal totalProfit,
        BigDecimal returnRate,
        String returnRateStatus,
        long pendingOrderCount,
        List<HoldingResponse> holdings
) {
}
