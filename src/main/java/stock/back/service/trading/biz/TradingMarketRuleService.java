package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.biz.KoreanStockTickSizePolicy;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.trading.vo.OrderRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TradingMarketRuleService {

    private static final BigDecimal DEFAULT_PRICE_LIMIT_RATE = BigDecimal.valueOf(30);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockPriceCacheService stockPriceCacheService;

    void validateSymbolExists(String symbol, MarketType marketType) {
        boolean exists = marketType == MarketType.ORDER_BOOK
                ? stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue(symbol)
                : stockInstrumentRepository.existsById(symbol);
        if (!exists) {
            throw StockException.notFound("Unknown stock symbol: " + symbol);
        }
    }

    void validateLimitPriceRule(String symbol, MarketType marketType, OrderType orderType, BigDecimal limitPrice) {
        if (orderType != OrderType.LIMIT || limitPrice == null) {
            return;
        }
        MarketPriceRule rule = resolveMarketPriceRule(symbol, marketType);
        BigDecimal tickSize = KoreanStockTickSizePolicy.tickSizeForQuotePrice(rule.market(), limitPrice);
        if (!KoreanStockTickSizePolicy.isValidQuotePrice(rule.market(), limitPrice)) {
            throw StockException.badRequest("Limit price must match tick size " + tickSize.stripTrailingZeros().toPlainString());
        }

        BigDecimal lowerLimit = KoreanStockTickSizePolicy.ceilingValidQuotePrice(rule.market(), rule.basePrice()
                .multiply(ONE_HUNDRED.subtract(rule.priceLimitRate()))
                .divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
        BigDecimal upperLimit = KoreanStockTickSizePolicy.floorValidQuotePrice(rule.market(), rule.basePrice()
                .multiply(ONE_HUNDRED.add(rule.priceLimitRate()))
                .divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
        if (limitPrice.compareTo(lowerLimit) < 0 || limitPrice.compareTo(upperLimit) > 0) {
            throw StockException.badRequest(
                    "Limit price must be between " + lowerLimit.toPlainString() + " and " + upperLimit.toPlainString()
            );
        }
    }

    BigDecimal calculateReservedCash(OrderRequest request, String symbol) {
        if (request.side() == OrderSide.SELL) {
            return BigDecimal.ZERO;
        }
        BigDecimal price = request.orderType() == OrderType.MARKET ? resolveReferencePrice(symbol) : request.limitPrice();
        return price.multiply(BigDecimal.valueOf(request.quantity()));
    }

    private MarketPriceRule resolveMarketPriceRule(String symbol, MarketType marketType) {
        if (marketType != MarketType.ORDER_BOOK) {
            StockPrice price = stockPriceRepository.findById(symbol)
                    .orElseThrow(() -> StockException.notFound("Price not found: " + symbol));
            return new MarketPriceRule(price.getPreviousClose(), "VIRTUAL_PRICE", DEFAULT_PRICE_LIMIT_RATE);
        }
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.findById(symbol)
                .orElseThrow(() -> StockException.notFound("Unknown stock symbol: " + symbol));
        BigDecimal basePrice = stockPriceRepository.findById(symbol)
                .map(StockPrice::getPreviousClose)
                .orElse(instrument.getInitialPrice());
        BigDecimal priceLimitRate = instrument.getPriceLimitRate() == null ? DEFAULT_PRICE_LIMIT_RATE : instrument.getPriceLimitRate();
        return new MarketPriceRule(basePrice, instrument.getMarket(), priceLimitRate);
    }

    private BigDecimal resolveReferencePrice(String symbol) {
        return resolveCurrentPrice(symbol)
                .orElseThrow(() -> StockException.notFound("Price not found: " + symbol));
    }

    private Optional<BigDecimal> resolveCurrentPrice(String symbol) {
        Optional<BigDecimal> cachedPrice = stockPriceCacheService.getCachedPrice(symbol)
                .map(CachedStockPrice::currentPrice);
        if (cachedPrice.isPresent()) {
            return cachedPrice;
        }
        return stockPriceRepository.findById(symbol)
                .map(StockPrice::getCurrentPrice);
    }

    private record MarketPriceRule(
            BigDecimal basePrice,
            String market,
            BigDecimal priceLimitRate
    ) {
    }
}
