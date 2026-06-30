package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.ListingAutoAccountRequest;
import stock.back.service.market.vo.ListingAutoAccountResponse;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AutoMarketConfigService {

    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final ListingAutoAccountLedgerQueryService listingAutoAccountLedgerQueryService;

    @Transactional
    public ListingAutoAccountResponse updateListingAutoAccountConfig(String symbol, ListingAutoAccountRequest request) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (request == null) {
            throw StockException.badRequest("Listing auto account update is required");
        }
        StockListingAutoAccountConfig config = stockListingAutoAccountConfigRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Listing auto account not found: " + normalizedSymbol));
        String displayName = request.displayName() == null ? null : normalizeText(request.displayName());
        if (displayName != null && displayName.length() > 80) {
            throw StockException.badRequest("Listing auto account display name must be 80 characters or less");
        }
        config.update(
                displayName,
                request.enabled(),
                request.positionSide(),
                request.maxOrderQuantity(),
                request.orderTtlSeconds(),
                request.priceOffsetTicks()
        );
        ListingAutoAccountConfigValidator.validate(config);
        return toListingAutoAccountResponse(config);
    }

    @Transactional
    public AutoMarketConfigResponse updateAutoMarketConfig(String symbol, AutoMarketConfigUpdateRequest request) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        StockAutoMarketConfig config = stockAutoMarketConfigRepository.findById(normalizedSymbol)
                .orElseGet(() -> StockAutoMarketConfig.defaults(normalizedSymbol));
        Integer intensity = request == null ? null : request.intensity();
        Integer maxOrderQuantity = request == null ? null : request.maxOrderQuantity();
        Integer orderTtlSeconds = request == null ? null : request.orderTtlSeconds();
        if (intensity != null && (intensity < 1 || intensity > 10)) {
            throw StockException.badRequest("Intensity must be between 1 and 10");
        }
        if (maxOrderQuantity != null && maxOrderQuantity <= 0) {
            throw StockException.badRequest("Max order quantity must be positive");
        }
        if (orderTtlSeconds != null && orderTtlSeconds <= 0) {
            throw StockException.badRequest("Order TTL seconds must be positive");
        }
        config.update(
                request == null ? null : request.enabled(),
                intensity,
                maxOrderQuantity,
                orderTtlSeconds
        );
        return toAutoMarketConfigResponse(stockAutoMarketConfigRepository.save(config));
    }

    private ListingAutoAccountResponse toListingAutoAccountResponse(StockListingAutoAccountConfig config) {
        ListingAutoAccountLedger ledger = listingAutoAccountLedgerQueryService.findLedger(config);
        return new ListingAutoAccountResponse(
                config.getSymbol(),
                config.getUserKey(),
                config.getDisplayName(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getPositionSide(),
                ledger.accountId(),
                ledger.cashBalance(),
                ledger.holdingQuantity(),
                ledger.reservedQuantity(),
                ledger.availableQuantity(),
                ledger.averagePrice(),
                ledger.currentPrice(),
                ledger.marketValue(),
                config.getMaxOrderQuantity(),
                config.getOrderTtlSeconds(),
                config.getPriceOffsetTicks(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private AutoMarketConfigResponse toAutoMarketConfigResponse(StockAutoMarketConfig config) {
        return new AutoMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getIntensity() == null ? 0 : config.getIntensity(),
                config.getMaxOrderQuantity() == null ? 0 : config.getMaxOrderQuantity(),
                config.getOrderTtlSeconds() == null ? 0 : config.getOrderTtlSeconds()
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
