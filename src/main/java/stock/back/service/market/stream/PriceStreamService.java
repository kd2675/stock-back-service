package stock.back.service.market.stream;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import stock.back.service.common.config.StockBackExecutorNames;
import stock.back.service.market.biz.SimulationClockService;

@Slf4j
@Service
public class PriceStreamService implements MessageListener {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final String PRICE_CHANNEL_PREFIX = "stock.price.";

    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;
    private final TaskExecutor priceStreamTaskExecutor;
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    public PriceStreamService(
            ObjectMapper objectMapper,
            SimulationClockService simulationClockService,
            @Qualifier(StockBackExecutorNames.PRICE_STREAM) TaskExecutor priceStreamTaskExecutor
    ) {
        this.objectMapper = objectMapper;
        this.simulationClockService = simulationClockService;
        this.priceStreamTaskExecutor = priceStreamTaskExecutor;
    }

    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));
        sendConnected(emitter);
        return emitter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        PriceStreamEvent event = parseMessage(message);
        if (event == null) {
            return;
        }
        try {
            priceStreamTaskExecutor.execute(() -> broadcast(event));
        } catch (RejectedExecutionException ex) {
            log.debug("SSE price stream event dropped: symbol={}, reason={}", event.symbol(), ex.getMessage());
        }
    }

    int connectedCount() {
        return emitters.size();
    }

    void broadcast(PriceStreamEvent event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("price")
                        .id(event.symbol() + ":" + event.priceTime())
                        .data(event));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter);
            } catch (RuntimeException ex) {
                log.debug("SSE price stream emitter removed: reason={}", ex.getMessage());
                emitters.remove(emitter);
            }
        }
    }

    private void sendConnected(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(emitter);
        } catch (RuntimeException ex) {
            log.debug("SSE price stream connection closed before welcome event: reason={}", ex.getMessage());
            emitters.remove(emitter);
        }
    }

    PriceStreamEvent parseMessage(Message message) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            if (payload.trim().startsWith("{")) {
                return objectMapper.readValue(payload, PriceStreamEvent.class);
            }
            String symbol = resolveSymbol(channel);
            if (symbol.isBlank()) {
                return null;
            }
            return PriceStreamEvent.legacy(
                    symbol,
                    new BigDecimal(payload.trim()),
                    simulationClockService.currentMarketDateTime().toString()
            );
        } catch (RuntimeException | IOException ex) {
            log.debug("Redis price stream message skipped: channel={}, reason={}", channel, ex.getMessage());
            return null;
        }
    }

    private String resolveSymbol(String channel) {
        if (channel == null || !channel.startsWith(PRICE_CHANNEL_PREFIX)) {
            return "";
        }
        return channel.substring(PRICE_CHANNEL_PREFIX.length()).trim().toUpperCase();
    }
}
