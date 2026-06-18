package stock.back.service.trading.vo;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioResponse(
        AccountResponse account,
        BigDecimal marketValue,
        BigDecimal reservedBuyCash,
        BigDecimal totalAsset,
        BigDecimal returnRate,
        long pendingOrderCount,
        List<HoldingResponse> holdings
) {
}
