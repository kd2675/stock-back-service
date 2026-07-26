package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockInstrument;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.entity.StockPriceTick;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.database.repository.StockPriceTickRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.market.vo.InstrumentResponse;
import stock.back.service.market.vo.OrderBookInstrumentResponse;
import stock.back.service.market.vo.PriceResponse;
import stock.back.service.market.vo.PriceTickResponse;
import stock.back.service.market.vo.RankingResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class MarketCatalogQueryService {

    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockPriceTickRepository stockPriceTickRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final StockAccountRepository stockAccountRepository;
    private final StockPriceCacheService stockPriceCacheService;

    @Transactional(readOnly = true)
    public List<InstrumentResponse> getInstruments() {
        return stockInstrumentRepository.findByEnabledTrueOrderBySymbolAsc().stream()
                .map(this::toInstrumentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> getPrices() {
        List<StockPrice> prices = stockPriceRepository.findVirtualMarketPrices();
        Map<String, CachedStockPrice> cachedPricesBySymbol = stockPriceCacheService.getCachedPrices(prices.stream()
                .filter(price -> price.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0)
                .map(StockPrice::getSymbol)
                .toList());
        return prices.stream()
                .map(price -> toPriceResponse(price, cachedPricesBySymbol))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderBookInstrumentResponse> getOrderBookInstruments() {
        List<StockOrderBookInstrument> instruments = stockOrderBookInstrumentRepository.findByEnabledTrueOrderBySymbolAsc();
        if (instruments.isEmpty()) {
            return List.of();
        }
        Map<String, StockPrice> pricesBySymbol = stockPriceRepository.findAllById(
                        instruments.stream()
                                .map(StockOrderBookInstrument::getSymbol)
                                .toList()
                )
                .stream()
                .collect(Collectors.toMap(StockPrice::getSymbol, Function.identity()));
        return instruments.stream()
                .map(instrument -> toOrderBookInstrumentResponse(instrument, pricesBySymbol.get(instrument.getSymbol())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RankingResponse> getRankings() {
        LocalDate rankingDate = portfolioSnapshotRepository.findTopRankingEligibleByOrderBySnapshotDateDesc()
                .map(PortfolioSnapshot::getSnapshotDate)
                .orElse(null);
        if (rankingDate == null) {
            return List.of();
        }
        List<PortfolioSnapshot> snapshots = portfolioSnapshotRepository.findTop20BySnapshotDateOrderByReturnRateDesc(rankingDate);
        if (snapshots.isEmpty()) {
            return List.of();
        }
        Map<Long, String> userKeysByAccountId = stockAccountRepository.findAllById(
                        snapshots.stream()
                                .map(PortfolioSnapshot::getAccountId)
                                .distinct()
                                .toList()
                ).stream()
                .collect(Collectors.toMap(
                        StockAccount::getId,
                        StockAccount::getUserKey,
                        (left, right) -> left
                ));
        return IntStream.range(0, snapshots.size())
                .mapToObj(index -> toRankingResponse(
                        index + 1,
                        snapshots.get(index),
                        userKeysByAccountId.get(snapshots.get(index).getAccountId())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PriceTickResponse> getPriceTicks(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        return stockPriceTickRepository.findTop100BySymbolOrderByPriceTimeDesc(normalizedSymbol).stream()
                .map(this::toPriceTickResponse)
                .toList();
    }

    private InstrumentResponse toInstrumentResponse(StockInstrument instrument) {
        return new InstrumentResponse(instrument.getSymbol(), instrument.getName(), instrument.getMarket());
    }

    private OrderBookInstrumentResponse toOrderBookInstrumentResponse(StockOrderBookInstrument instrument, StockPrice price) {
        BigDecimal currentPrice = price == null ? instrument.getInitialPrice() : price.getCurrentPrice();
        BigDecimal priceLimitBase = price == null ? instrument.getInitialPrice() : price.getPreviousClose();
        LocalDateTime priceTime = price == null ? instrument.getUpdatedAt() : price.getPriceTime();
        String priceProvider = price == null ? "order-book-initial" : price.getProvider();
        return new OrderBookInstrumentResponse(
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getMarket(),
                instrument.getInitialPrice(),
                instrument.getIssuedShares(),
                instrument.getTradableShares(),
                KoreanStockTickSizePolicy.tickSizeForCurrentPrice(instrument.getMarket(), currentPrice),
                instrument.getPriceLimitRate(),
                priceLimitBase,
                currentPrice,
                priceTime,
                priceProvider,
                Boolean.TRUE.equals(instrument.getEnabled()),
                instrument.getCreatedAt(),
                instrument.getUpdatedAt()
        );
    }

    private PriceResponse toPriceResponse(StockPrice price, Map<String, CachedStockPrice> cachedPricesBySymbol) {
        CachedStockPrice cachedPrice = cachedPricesBySymbol.get(price.getSymbol());
        BigDecimal currentPrice = cachedPrice == null ? price.getCurrentPrice() : cachedPrice.currentPrice();
        String provider = cachedPrice == null ? price.getProvider() : cachedPrice.provider();

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

    private RankingResponse toRankingResponse(int rank, PortfolioSnapshot snapshot, String userKey) {
        return new RankingResponse(
                rank,
                snapshot.getAccountId(),
                userKey,
                toRankingDisplayName(userKey),
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

}
