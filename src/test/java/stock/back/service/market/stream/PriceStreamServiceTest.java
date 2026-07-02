package stock.back.service.market.stream;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import stock.back.service.market.biz.SimulationClockService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PriceStreamServiceTest {

    private static final LocalDateTime SIMULATION_NOW = LocalDateTime.of(2026, 7, 1, 10, 0);

    private final SimulationClockService simulationClockService = mock(SimulationClockService.class);
    private final PriceStreamService priceStreamService = new PriceStreamService(new ObjectMapper(), simulationClockService);

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
        when(simulationClockService.currentMarketDateTime()).thenReturn(SIMULATION_NOW);

        PriceStreamEvent event = priceStreamService.parseMessage(message("stock.price.005930", "70100.00"));

        assertThat(event).isNotNull();
        assertThat(event.symbol()).isEqualTo("005930");
        assertThat(event.currentPrice()).isEqualByComparingTo(new BigDecimal("70100.00"));
        assertThat(event.priceTime()).isEqualTo(SIMULATION_NOW.toString());
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
