package stock.back.service.market.stream;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PriceStreamEvent(
        String symbol,
        BigDecimal currentPrice,
        String priceTime,
        String provider
) {
    public static PriceStreamEvent legacy(String symbol, BigDecimal currentPrice) {
        return new PriceStreamEvent(symbol, currentPrice, LocalDateTime.now().toString(), "redis-pubsub");
    }
}
