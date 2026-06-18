package stock.back.service.market.cache;

import java.math.BigDecimal;

public record CachedStockPrice(
        BigDecimal currentPrice,
        String provider
) {
}
