package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AdminOrderFlowSummaryResponse(
        long openOrderCount,
        long openBuyOrderCount,
        long openSellOrderCount,
        long partiallyFilledOrderCount,
        BigDecimal reservedBuyCash,
        long reservedSellQuantity,
        long todayOrderCount,
        long todayFilledOrderCount,
        long todayCancelledOrderCount,
        long todayRejectedOrderCount
) {
}
