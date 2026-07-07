package stock.back.service.trading.biz;

import java.util.List;
import java.util.Locale;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import stock.back.service.database.entity.MarketType;

@Slf4j
@Component
class OrderBookReadySymbolPublisher {

    private static final RedisScript<Long> ENQUEUE_SCRIPT = RedisScript.of(
            """
            if redis.call('sadd', KEYS[1], ARGV[1]) == 1 then
                return redis.call('rpush', KEYS[2], ARGV[1])
            end
            return 0
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final String setKey;
    private final String queueKey;

    OrderBookReadySymbolPublisher(
            StringRedisTemplate redisTemplate,
            @Value("${stock.order-book.execution.ready-symbol-queue.set-key:stock:orderbook:execution:ready-symbol-set}") String setKey,
            @Value("${stock.order-book.execution.ready-symbol-queue.queue-key:stock:orderbook:execution:ready-symbol-queue}") String queueKey
    ) {
        this.redisTemplate = redisTemplate;
        this.setKey = requireKey(setKey, "set-key");
        this.queueKey = requireKey(queueKey, "queue-key");
    }

    void enqueueAfterCommit(String symbol, MarketType marketType) {
        if (marketType != MarketType.ORDER_BOOK) {
            return;
        }
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            return;
        }
        Runnable enqueueAction = () -> enqueue(normalizedSymbol);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueueAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enqueueAction.run();
            }
        });
    }

    private void enqueue(String symbol) {
        try {
            redisTemplate.execute(ENQUEUE_SCRIPT, List.of(setKey, queueKey), symbol);
        } catch (RuntimeException ex) {
            log.warn("Order book ready symbol publish failed: symbol={}, reason={}", symbol, ex.getMessage());
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String requireKey(String key, String propertyName) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("stock.order-book.execution.ready-symbol-queue.%s must not be blank".formatted(propertyName));
        }
        return key;
    }
}
