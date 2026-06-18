package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.StockInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.entity.StockPriceTick;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockPriceTickRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.market.vo.InstrumentResponse;
import stock.back.service.market.vo.OrderBookLevelResponse;
import stock.back.service.market.vo.OrderBookResponse;
import stock.back.service.market.vo.PriceResponse;
import stock.back.service.market.vo.PriceTickResponse;
import stock.back.service.market.vo.RankingResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class MarketService {

    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockPriceTickRepository stockPriceTickRepository;
    private final StockOrderRepository stockOrderRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final StockPriceCacheService stockPriceCacheService;

    @Transactional(readOnly = true)
    public List<InstrumentResponse> getInstruments() {
        return stockInstrumentRepository.findByEnabledTrueOrderBySymbolAsc().stream()
                .map(this::toInstrumentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> getPrices() {
        return stockPriceRepository.findAllByOrderBySymbolAsc().stream()
                .map(this::toPriceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RankingResponse> getRankings() {
        LocalDate rankingDate = portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()
                .map(PortfolioSnapshot::getSnapshotDate)
                .orElse(null);
        if (rankingDate == null) {
            return List.of();
        }
        AtomicInteger rank = new AtomicInteger(1);
        return portfolioSnapshotRepository.findTop20BySnapshotDateOrderByReturnRateDesc(rankingDate).stream()
                .map(snapshot -> toRankingResponse(rank.getAndIncrement(), snapshot))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PriceTickResponse> getPriceTicks(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        return stockPriceTickRepository.findTop100BySymbolOrderByPriceTimeDesc(normalizedSymbol).stream()
                .map(this::toPriceTickResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderBookResponse getOrderBook(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown stock symbol: " + normalizedSymbol);
        }
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        var page = PageRequest.of(0, 10);
        List<OrderBookLevelResponse> bids = stockOrderRepository
                .findBidLevels(normalizedSymbol, OrderSide.BUY, OrderType.LIMIT, openStatuses, page)
                .stream()
                .map(this::toOrderBookLevelResponse)
                .toList();
        List<OrderBookLevelResponse> asks = stockOrderRepository
                .findAskLevels(normalizedSymbol, OrderSide.SELL, OrderType.LIMIT, openStatuses, page)
                .stream()
                .map(this::toOrderBookLevelResponse)
                .toList();
        return new OrderBookResponse(normalizedSymbol, bids, asks);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private InstrumentResponse toInstrumentResponse(StockInstrument instrument) {
        return new InstrumentResponse(instrument.getSymbol(), instrument.getName(), instrument.getMarket());
    }

    private PriceResponse toPriceResponse(StockPrice price) {
        var cachedPrice = stockPriceCacheService.getCachedPrice(price.getSymbol());
        BigDecimal currentPrice = cachedPrice
                .map(CachedStockPrice::currentPrice)
                .orElse(price.getCurrentPrice());
        String provider = cachedPrice
                .map(CachedStockPrice::provider)
                .orElse(price.getProvider());

        BigDecimal changeRate = BigDecimal.ZERO;
        if (price.getPreviousClose().compareTo(BigDecimal.ZERO) > 0) {
            changeRate = currentPrice
                    .subtract(price.getPreviousClose())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(price.getPreviousClose(), 4, RoundingMode.HALF_UP);
        }
        return new PriceResponse(
                price.getSymbol(),
                currentPrice,
                price.getPreviousClose(),
                changeRate,
                price.getPriceTime(),
                provider
        );
    }

    private RankingResponse toRankingResponse(int rank, PortfolioSnapshot snapshot) {
        return new RankingResponse(
                rank,
                snapshot.getUserKey(),
                toRankingDisplayName(snapshot.getUserKey()),
                snapshot.getTotalAsset(),
                snapshot.getReturnRate(),
                snapshot.getSnapshotDate()
        );
    }

    private String toRankingDisplayName(String userKey) {
        if (userKey == null || userKey.isBlank()) {
            return "익명 투자자";
        }
        String normalized = userKey.trim();
        int visibleLength = Math.min(6, normalized.length());
        return "투자자 " + normalized.substring(normalized.length() - visibleLength);
    }

    private PriceTickResponse toPriceTickResponse(StockPriceTick tick) {
        return new PriceTickResponse(tick.getSymbol(), tick.getPrice(), tick.getProvider(), tick.getPriceTime());
    }

    private OrderBookLevelResponse toOrderBookLevelResponse(StockOrderRepository.OrderBookLevelView level) {
        return new OrderBookLevelResponse(
                level.getPrice(),
                level.getQuantity() == null ? 0L : level.getQuantity(),
                level.getOrderCount() == null ? 0L : level.getOrderCount()
        );
    }
}
