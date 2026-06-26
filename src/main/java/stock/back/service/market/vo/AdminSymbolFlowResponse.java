package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminSymbolFlowResponse(
        String symbol,
        String name,
        boolean enabled,
        String marketStatus,
        long issuedShares,
        long tradableShares,
        BigDecimal currentPrice,
        BigDecimal previousClose,
        BigDecimal changeRate,
        long executionCount,
        long executionQuantity,
        BigDecimal turnoverAmount,
        long buyQuantity,
        long sellQuantity,
        BigDecimal buyNetAmount,
        BigDecimal sellNetAmount,
        long openOrderCount,
        long openBuyOrderCount,
        long openSellOrderCount,
        BigDecimal reservedBuyCash,
        long holderCount,
        long holdingQuantity,
        long pendingCorporateActionCount,
        LocalDateTime lastExecutedAt
) {
}
