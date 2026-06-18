package stock.back.service.market.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockPriceCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private StockPriceCacheService stockPriceCacheService;

    @BeforeEach
    void setUp() {
        stockPriceCacheService = new StockPriceCacheService(redisTemplate);
    }

    @Test
    void getCachedPrice_validRedisValue_returnsCachedPrice() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("stock:price:005930")).thenReturn("71000.25");

        var cachedPrice = stockPriceCacheService.getCachedPrice("005930");

        assertThat(cachedPrice).isPresent();
        assertThat(cachedPrice.orElseThrow().currentPrice()).isEqualByComparingTo(new BigDecimal("71000.25"));
        assertThat(cachedPrice.orElseThrow().provider()).isEqualTo("redis-cache");
    }

    @Test
    void getCachedPrice_invalidRedisValue_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("stock:price:005930")).thenReturn("bad-price");

        var cachedPrice = stockPriceCacheService.getCachedPrice("005930");

        assertThat(cachedPrice).isEmpty();
    }

    @Test
    void getCachedPrice_symbolWithWhitespaceAndLowercase_readsNormalizedRedisKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("stock:price:ABC123")).thenReturn("51000.00");

        var cachedPrice = stockPriceCacheService.getCachedPrice(" abc123 ");

        assertThat(cachedPrice).isPresent();
        assertThat(cachedPrice.orElseThrow().currentPrice()).isEqualByComparingTo(new BigDecimal("51000.00"));
    }

    @Test
    void getCachedPrice_blankSymbol_returnsEmptyWithoutRedisRead() {
        var cachedPrice = stockPriceCacheService.getCachedPrice(" ");

        assertThat(cachedPrice).isEmpty();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void getCachedPrice_redisFailure_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        var cachedPrice = stockPriceCacheService.getCachedPrice("005930");

        assertThat(cachedPrice).isEmpty();
    }
}
