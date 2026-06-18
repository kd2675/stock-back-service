package stock.back.service.trading.vo;

import java.math.BigDecimal;

public record HoldingResponse(
        String symbol,
        long quantity,
        long reservedQuantity,
        long availableQuantity,
        BigDecimal averagePrice,
        BigDecimal currentPrice,
        BigDecimal marketValue,
        BigDecimal unrealizedProfit
) {
}
