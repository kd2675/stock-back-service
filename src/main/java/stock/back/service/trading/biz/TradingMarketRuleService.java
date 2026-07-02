package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.entity.StockVirtualMarketConfig;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.database.repository.StockVirtualMarketConfigRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.trading.vo.OrderRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TradingMarketRuleService {

    private static final BigDecimal DEFAULT_TICK_SIZE = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_PRICE_LIMIT_RATE = BigDecimal.valueOf(30);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockVirtualMarketConfigRepository stockVirtualMarketConfigRepository;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
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

    void validateMarketOpen(String symbol, MarketType marketType) {
        if (marketType == MarketType.ORDER_BOOK) {
            StockOrderBookMarketConfig config = stockOrderBookMarketConfigRepository.findById(symbol)
                    .orElseThrow(() -> StockException.conflict("Market is not open: " + symbol));
            if (!Boolean.TRUE.equals(config.getEnabled()) || normalizeMarketSessionStatus(config.getMarketStatus()) != MarketSessionStatus.OPEN) {
                throw StockException.conflict("Market is not open: " + symbol);
            }
            return;
        }

        StockVirtualMarketConfig config = stockVirtualMarketConfigRepository.findById(symbol)
                .orElseThrow(() -> StockException.conflict("Market is not open: " + symbol));
        if (!Boolean.TRUE.equals(config.getEnabled()) || normalizeMarketSessionStatus(config.getMarketStatus()) != MarketSessionStatus.OPEN) {
            throw StockException.conflict("Market is not open: " + symbol);
        }
    }

    void validateLimitPriceRule(String symbol, MarketType marketType, OrderType orderType, BigDecimal limitPrice) {
        if (orderType != OrderType.LIMIT || limitPrice == null) {
            return;
        }
        MarketPriceRule rule = resolveMarketPriceRule(symbol, marketType);
        if (limitPrice.remainder(rule.tickSize()).compareTo(BigDecimal.ZERO) != 0) {
            throw StockException.badRequest("Limit price must match tick size " + rule.tickSize().stripTrailingZeros().toPlainString());
        }

        BigDecimal lowerLimit = rule.basePrice()
                .multiply(ONE_HUNDRED.subtract(rule.priceLimitRate()))
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal upperLimit = rule.basePrice()
                .multiply(ONE_HUNDRED.add(rule.priceLimitRate()))
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
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

    private MarketSessionStatus normalizeMarketSessionStatus(MarketSessionStatus marketStatus) {
        return marketStatus == null ? MarketSessionStatus.OPEN : marketStatus;
    }

    private MarketPriceRule resolveMarketPriceRule(String symbol, MarketType marketType) {
        if (marketType != MarketType.ORDER_BOOK) {
            StockPrice price = stockPriceRepository.findById(symbol)
                    .orElseThrow(() -> StockException.notFound("Price not found: " + symbol));
            return new MarketPriceRule(price.getPreviousClose(), DEFAULT_TICK_SIZE, DEFAULT_PRICE_LIMIT_RATE);
        }
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.findById(symbol)
                .orElseThrow(() -> StockException.notFound("Unknown stock symbol: " + symbol));
        BigDecimal basePrice = stockPriceRepository.findById(symbol)
                .map(StockPrice::getPreviousClose)
                .orElse(instrument.getInitialPrice());
        BigDecimal tickSize = instrument.getTickSize() == null ? DEFAULT_TICK_SIZE : instrument.getTickSize();
        BigDecimal priceLimitRate = instrument.getPriceLimitRate() == null ? DEFAULT_PRICE_LIMIT_RATE : instrument.getPriceLimitRate();
        return new MarketPriceRule(basePrice, tickSize, priceLimitRate);
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
            BigDecimal tickSize,
            BigDecimal priceLimitRate
    ) {
    }
}
