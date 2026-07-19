package stock.back.service.market.biz;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import stock.back.service.market.vo.OrderBookCandleResponse;
import stock.back.service.market.vo.OrderBookTradeSummaryResponse;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Collapses identical polling requests before they reach the append-only execution ledger. This cache is
 * deliberately read-side only: order placement and matching never wait for it and no trade transaction
 * performs a cache or summary write.
 */
@Service
public class OrderBookLiveAggregateCacheService {

    static final long MIN_TTL_MILLIS = 1_000L;
    static final long MAX_TTL_MILLIS = 60_000L;
    static final int MIN_CACHE_ENTRIES = 10;
    static final int MAX_CACHE_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, CacheEntry<OrderBookTradeSummaryResponse>> tradeSummaryCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CandleCacheKey, CacheEntry<List<OrderBookCandleResponse>>> candleCache =
            new ConcurrentHashMap<>();
    private final OrderBookQueryService orderBookQueryService;
    private final OrderBookCandleQueryService orderBookCandleQueryService;
    private final MeterRegistry meterRegistry;
    private final long tradeSummaryTtlNanos;
    private final long candleTtlNanos;
    private final int maxEntries;

    public OrderBookLiveAggregateCacheService(
            OrderBookQueryService orderBookQueryService,
            OrderBookCandleQueryService orderBookCandleQueryService,
            MeterRegistry meterRegistry,
            @Value("${stock.back.order-book-live-aggregate-cache.trade-summary-ttl-ms:10000}") long tradeSummaryTtlMillis,
            @Value("${stock.back.order-book-live-aggregate-cache.candle-ttl-ms:10000}") long candleTtlMillis,
            @Value("${stock.back.order-book-live-aggregate-cache.max-entries:1000}") int maxEntries
    ) {
        validateTtl("trade-summary-ttl-ms", tradeSummaryTtlMillis);
        validateTtl("candle-ttl-ms", candleTtlMillis);
        if (maxEntries < MIN_CACHE_ENTRIES || maxEntries > MAX_CACHE_ENTRIES) {
            throw new IllegalArgumentException(
                    "order-book live aggregate cache max-entries must be between %d and %d: %d"
                            .formatted(MIN_CACHE_ENTRIES, MAX_CACHE_ENTRIES, maxEntries)
            );
        }
        this.orderBookQueryService = orderBookQueryService;
        this.orderBookCandleQueryService = orderBookCandleQueryService;
        this.meterRegistry = meterRegistry;
        this.tradeSummaryTtlNanos = tradeSummaryTtlMillis * 1_000_000L;
        this.candleTtlNanos = candleTtlMillis * 1_000_000L;
        this.maxEntries = maxEntries;
    }

    public OrderBookTradeSummaryResponse getTradeSummary(String symbol) {
        String normalizedSymbol = normalize(symbol);
        return getOrLoad(
                tradeSummaryCache,
                normalizedSymbol,
                tradeSummaryTtlNanos,
                "trade-summary",
                () -> orderBookQueryService.getOrderBookTradeSummary(normalizedSymbol)
        );
    }

    public List<OrderBookCandleResponse> getCandles(String symbol, String interval) {
        CandleCacheKey key = new CandleCacheKey(normalize(symbol), normalize(interval));
        return getOrLoad(
                candleCache,
                key,
                candleTtlNanos,
                "candle",
                () -> List.copyOf(orderBookCandleQueryService.getOrderBookCandles(key.symbol(), key.interval()))
        );
    }

    private <K, V> V getOrLoad(
            ConcurrentHashMap<K, CacheEntry<V>> cache,
            K key,
            long ttlNanos,
            String view,
            Supplier<V> loader
    ) {
        long now = System.nanoTime();
        CacheEntry<V> existing = cache.get(key);
        if (existing != null && existing.isUsable(now)) {
            record(view, existing.future().isDone() ? "hit" : "single-flight");
            return await(existing.future());
        }
        if (existing != null) {
            cache.remove(key, existing);
        }

        trim(cache, now);
        CacheEntry<V> created = CacheEntry.loading(now);
        existing = cache.putIfAbsent(key, created);
        if (existing != null) {
            record(view, existing.future().isDone() ? "hit" : "single-flight");
            return await(existing.future());
        }

        record(view, "miss");
        try {
            V value = loader.get();
            created.complete(value, ttlNanos);
            return value;
        } catch (RuntimeException ex) {
            created.future().completeExceptionally(ex);
            cache.remove(key, created);
            throw ex;
        }
    }

    private <K, V> void trim(ConcurrentHashMap<K, CacheEntry<V>> cache, long now) {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        while (cache.size() >= maxEntries) {
            K evictionKey = cache.entrySet().stream()
                    .filter(entry -> entry.getValue().future().isDone())
                    .min(Comparator.comparingLong(entry -> entry.getValue().createdAtNanos()))
                    .map(java.util.Map.Entry::getKey)
                    .orElse(null);
            if (evictionKey == null) {
                throw new IllegalStateException("order-book live aggregate cache is saturated with in-flight loads");
            }
            cache.remove(evictionKey);
            record("all", "eviction");
        }
    }

    private <V> V await(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }

    private void record(String view, String result) {
        meterRegistry.counter(
                "stock.orderbook.live.aggregate.cache.requests",
                "view",
                view,
                "result",
                result
        ).increment();
    }

    private static void validateTtl(String property, long value) {
        if (value < MIN_TTL_MILLIS || value > MAX_TTL_MILLIS) {
            throw new IllegalArgumentException(
                    "order-book live aggregate cache %s must be between %d and %d: %d"
                            .formatted(property, MIN_TTL_MILLIS, MAX_TTL_MILLIS, value)
            );
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record CandleCacheKey(String symbol, String interval) {
    }

    private static final class CacheEntry<V> {

        private final CompletableFuture<V> future = new CompletableFuture<>();
        private final long createdAtNanos;
        private volatile long expiresAtNanos = Long.MAX_VALUE;

        private CacheEntry(long createdAtNanos) {
            this.createdAtNanos = createdAtNanos;
        }

        private static <V> CacheEntry<V> loading(long createdAtNanos) {
            return new CacheEntry<>(createdAtNanos);
        }

        private CompletableFuture<V> future() {
            return future;
        }

        private long createdAtNanos() {
            return createdAtNanos;
        }

        private void complete(V value, long ttlNanos) {
            expiresAtNanos = System.nanoTime() + ttlNanos;
            future.complete(value);
        }

        private boolean isUsable(long now) {
            return !future.isDone() || now < expiresAtNanos;
        }

        private boolean isExpired(long now) {
            return future.isDone() && now >= expiresAtNanos;
        }
    }
}
