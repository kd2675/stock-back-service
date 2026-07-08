package stock.back.service.market.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            return parseCachedPrice(rawPrice)
                    .map(currentPrice -> new CachedStockPrice(currentPrice, CACHE_PROVIDER));
        } catch (RuntimeException ex) {
            log.warn("Redis price cache read skipped: symbol={}, reason={}", symbol, ex.getMessage());
            return Optional.empty();
        }
    }

    public Map<String, CachedStockPrice> getCachedPrices(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        Map<String, String> keysBySymbol = new LinkedHashMap<>();
        for (String symbol : symbols) {
            String normalizedSymbol = normalizeSymbol(symbol);
            if (!normalizedSymbol.isBlank()) {
                keysBySymbol.putIfAbsent(normalizedSymbol, PRICE_KEY_PREFIX + normalizedSymbol);
            }
        }
        if (keysBySymbol.isEmpty()) {
            return Map.of();
        }
        try {
            List<String> keys = List.copyOf(keysBySymbol.values());
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            Map<String, CachedStockPrice> cachedPricesBySymbol = new LinkedHashMap<>();
            List<String> normalizedSymbols = List.copyOf(keysBySymbol.keySet());
            for (int i = 0; i < normalizedSymbols.size() && i < values.size(); i++) {
                String normalizedSymbol = normalizedSymbols.get(i);
                parseCachedPrice(values.get(i))
                        .ifPresent(price -> cachedPricesBySymbol.put(
                                normalizedSymbol,
                                new CachedStockPrice(price, CACHE_PROVIDER)
                        ));
            }
            return cachedPricesBySymbol;
        } catch (RuntimeException ex) {
            log.warn("Redis price cache batch read skipped: symbols={}, reason={}", symbols, ex.getMessage());
            return Map.of();
        }
    }

    private Optional<BigDecimal> parseCachedPrice(String rawPrice) {
        if (rawPrice == null || rawPrice.isBlank()) {
            return Optional.empty();
        }
        try {
            BigDecimal currentPrice = new BigDecimal(rawPrice.trim());
            if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                return Optional.empty();
            }
            return Optional.of(currentPrice);
        } catch (RuntimeException ex) {
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
