package stock.back.service.market.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockPriceCacheService {

    private static final String PRICE_KEY_PREFIX = "stock:price:";
    private static final String CACHE_PROVIDER = "redis-cache";

    private final StringRedisTemplate redisTemplate;

    public Optional<CachedStockPrice> getCachedPrice(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            return Optional.empty();
        }
        try {
            String rawPrice = redisTemplate.opsForValue().get(PRICE_KEY_PREFIX + normalizedSymbol);
            if (rawPrice == null || rawPrice.isBlank()) {
                return Optional.empty();
            }
            BigDecimal currentPrice = new BigDecimal(rawPrice.trim());
            if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                return Optional.empty();
            }
            return Optional.of(new CachedStockPrice(currentPrice, CACHE_PROVIDER));
        } catch (RuntimeException ex) {
            log.debug("Redis price cache read skipped: symbol={}, reason={}", symbol, ex.getMessage());
            return Optional.empty();
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
