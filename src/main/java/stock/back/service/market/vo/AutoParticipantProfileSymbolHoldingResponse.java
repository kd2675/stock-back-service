package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AutoParticipantProfileSymbolHoldingResponse(
        String symbol,
        long holderCount,
        long quantity,
        long reservedQuantity,
        long availableQuantity,
        BigDecimal marketValue,
        BigDecimal unrealizedProfit
) {
}
