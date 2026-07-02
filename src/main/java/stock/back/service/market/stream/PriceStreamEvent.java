package stock.back.service.market.stream;

import java.math.BigDecimal;

public record PriceStreamEvent(
        String symbol,
        BigDecimal currentPrice,
        String priceTime,
        String provider
) {
    public static PriceStreamEvent legacy(String symbol, BigDecimal currentPrice, String priceTime) {
        return new PriceStreamEvent(symbol, currentPrice, priceTime, "redis-pubsub");
    }
}
