package stock.back.service.market.biz;

import java.math.BigDecimal;

record ListingAutoAccountLedger(
        Long accountId,
        BigDecimal cashBalance,
        long holdingQuantity,
        long reservedQuantity,
        long availableQuantity,
        BigDecimal averagePrice,
        BigDecimal currentPrice,
        BigDecimal marketValue,
        BigDecimal reservedBuyCash,
        long openBuyQuantity,
        long openSellQuantity
) {

    static ListingAutoAccountLedger empty() {
        return new ListingAutoAccountLedger(
                null,
                BigDecimal.ZERO,
                0L,
                0L,
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L
        );
    }

    static ListingAutoAccountLedger of(
            Long accountId,
            BigDecimal cashBalance,
            long holdingQuantity,
            long reservedQuantity,
            BigDecimal averagePrice,
            BigDecimal currentPrice
    ) {
        return of(accountId, cashBalance, holdingQuantity, reservedQuantity, averagePrice, currentPrice, BigDecimal.ZERO, 0L, 0L);
    }

    static ListingAutoAccountLedger of(
            Long accountId,
            BigDecimal cashBalance,
            long holdingQuantity,
            long reservedQuantity,
            BigDecimal averagePrice,
            BigDecimal currentPrice,
            BigDecimal reservedBuyCash,
            long openBuyQuantity,
            long openSellQuantity
    ) {
        long availableQuantity = Math.max(0L, holdingQuantity - reservedQuantity);
        BigDecimal marketValue = currentPrice.multiply(BigDecimal.valueOf(holdingQuantity));
        return new ListingAutoAccountLedger(
                accountId,
                cashBalance,
                holdingQuantity,
                reservedQuantity,
                availableQuantity,
                averagePrice,
                currentPrice,
                marketValue,
                reservedBuyCash == null ? BigDecimal.ZERO : reservedBuyCash.max(BigDecimal.ZERO),
                Math.max(0L, openBuyQuantity),
                Math.max(0L, openSellQuantity)
        );
    }
}
