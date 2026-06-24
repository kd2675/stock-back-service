package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AutoParticipantHoldingResponse(
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
