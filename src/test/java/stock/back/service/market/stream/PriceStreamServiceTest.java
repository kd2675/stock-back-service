package stock.back.service.market.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PriceStreamServiceTest {

    private final PriceStreamService priceStreamService = new PriceStreamService(new ObjectMapper());

    @Test
    void parseMessage_jsonPayload_returnsPriceStreamEvent() {
        String payload = """
                {
                  "symbol": "005930",
                  "currentPrice": 70100.00,
                  "priceTime": "2026-06-18T10:10:01",
                  "provider": "test-provider"
                }
                """;

        PriceStreamEvent event = priceStreamService.parseMessage(message("stock.price.005930", payload));

        assertThat(event).isNotNull();
        assertThat(event.symbol()).isEqualTo("005930");
        assertThat(event.currentPrice()).isEqualByComparingTo(new BigDecimal("70100.00"));
        assertThat(event.priceTime()).isEqualTo("2026-06-18T10:10:01");
        assertThat(event.provider()).isEqualTo("test-provider");
    }

    @Test
    void parseMessage_plainPricePayload_returnsLegacyPriceStreamEvent() {
        PriceStreamEvent event = priceStreamService.parseMessage(message("stock.price.005930", "70100.00"));

        assertThat(event).isNotNull();
        assertThat(event.symbol()).isEqualTo("005930");
        assertThat(event.currentPrice()).isEqualByComparingTo(new BigDecimal("70100.00"));
        assertThat(event.provider()).isEqualTo("redis-pubsub");
    }

    @Test
    void parseMessage_invalidPayload_returnsNull() {
        PriceStreamEvent event = priceStreamService.parseMessage(message("stock.price.005930", "bad-price"));

        assertThat(event).isNull();
    }

    @Test
    void broadcast_disconnectedEmitter_removesEmitter() {
        emitters().add(new BrokenPipeEmitter());

        priceStreamService.broadcast(new PriceStreamEvent("005930", new BigDecimal("70100.00"), "2026-06-23T17:37:01", "test"));

        assertThat(priceStreamService.connectedCount()).isZero();
    }

    private DefaultMessage message(String channel, String payload) {
        return new DefaultMessage(
                channel.getBytes(StandardCharsets.UTF_8),
                payload.getBytes(StandardCharsets.UTF_8)
        );
    }

    @SuppressWarnings("unchecked")
    private Set<SseEmitter> emitters() {
        return (Set<SseEmitter>) ReflectionTestUtils.getField(priceStreamService, "emitters");
    }

    private static final class BrokenPipeEmitter extends SseEmitter {

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new AsyncRequestNotUsableException("Servlet container error notification for disconnected client");
        }
    }
}
