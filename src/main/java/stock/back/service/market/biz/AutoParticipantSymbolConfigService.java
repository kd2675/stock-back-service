package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfigId;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AutoParticipantSymbolConfigService {

    private final StockAutoParticipantRepository stockAutoParticipantRepository;
    private final StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository;
    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Transactional
    public AutoParticipantSymbolConfigResponse updateAutoParticipantSymbolConfig(
            String userKey,
            String symbol,
            AutoParticipantSymbolConfigRequest request
    ) {
        String normalizedUserKey = normalizeText(userKey);
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedUserKey.isBlank()) {
            throw StockException.badRequest("Auto participant user key is required");
        }
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        StockAutoParticipant participant = stockAutoParticipantRepository.findById(normalizedUserKey)
                .orElseThrow(() -> StockException.notFound("Unknown auto participant: " + normalizedUserKey));
        if (participant.getWithdrawnAt() != null) {
            throw StockException.notFound("Unknown auto participant: " + normalizedUserKey);
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }

        StockAutoMarketConfig marketConfig = stockAutoMarketConfigRepository.findById(normalizedSymbol)
                .orElseGet(() -> StockAutoMarketConfig.defaults(normalizedSymbol));
        Integer intensity = request == null ? null : request.intensity();
        if (intensity != null && (intensity < 1 || intensity > 10)) {
            throw StockException.badRequest("Intensity must be between 1 and 10");
        }

        StockAutoParticipantSymbolConfigId id = new StockAutoParticipantSymbolConfigId(normalizedUserKey, normalizedSymbol);
        StockAutoParticipantSymbolConfig config = stockAutoParticipantSymbolConfigRepository.findById(id)
                .orElseGet(() -> StockAutoParticipantSymbolConfig.defaults(
                        normalizedUserKey,
                        normalizedSymbol,
                        marketConfig.getIntensity() == null ? 5 : marketConfig.getIntensity()
                ));
        config.update(request == null ? null : request.enabled(), intensity);
        return toAutoParticipantSymbolConfigResponse(stockAutoParticipantSymbolConfigRepository.save(config));
    }

    private AutoParticipantSymbolConfigResponse toAutoParticipantSymbolConfigResponse(StockAutoParticipantSymbolConfig config) {
        return new AutoParticipantSymbolConfigResponse(
                config.getUserKey(),
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getIntensity() == null ? 0 : config.getIntensity(),
                config.getUpdatedAt()
        );
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
