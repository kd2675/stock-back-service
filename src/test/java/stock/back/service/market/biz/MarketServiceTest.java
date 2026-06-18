package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.entity.StockPriceTick;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockPriceTickRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock
    private StockInstrumentRepository stockInstrumentRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private StockPriceTickRepository stockPriceTickRepository;

    @Mock
    private StockOrderRepository stockOrderRepository;

    @Mock
    private PortfolioSnapshotRepository portfolioSnapshotRepository;

    @Mock
    private StockPriceCacheService stockPriceCacheService;

    private MarketService marketService;

    @BeforeEach
    void setUp() {
        marketService = new MarketService(
                stockInstrumentRepository,
                stockPriceRepository,
                stockPriceTickRepository,
                stockOrderRepository,
                portfolioSnapshotRepository,
                stockPriceCacheService
        );
    }

    @Test
    void getPrices_cachedPriceExists_usesRedisPriceAndProvider() {
        when(stockPriceRepository.findAllByOrderBySymbolAsc())
                .thenReturn(List.of(StockPrice.initial("005930", new BigDecimal("70000.00"))));
        when(stockPriceCacheService.getCachedPrice("005930"))
                .thenReturn(Optional.of(new CachedStockPrice(new BigDecimal("71000.00"), "redis-cache")));

        var prices = marketService.getPrices();

        assertThat(prices).hasSize(1);
        assertThat(prices.get(0).currentPrice()).isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(prices.get(0).changeRate()).isEqualByComparingTo(new BigDecimal("1.4286"));
        assertThat(prices.get(0).provider()).isEqualTo("redis-cache");
    }

    @Test
    void getPrices_cachedPriceMissing_usesDatabasePrice() {
        when(stockPriceRepository.findAllByOrderBySymbolAsc())
                .thenReturn(List.of(StockPrice.initial("005930", new BigDecimal("70000.00"))));
        when(stockPriceCacheService.getCachedPrice("005930")).thenReturn(Optional.empty());

        var prices = marketService.getPrices();

        assertThat(prices.get(0).currentPrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(prices.get(0).provider()).isEqualTo("seed");
    }

    @Test
    void getPriceTicks_existingTicks_returnsLatestTickResponses() {
        StockPriceTick tick = org.mockito.Mockito.mock(StockPriceTick.class);
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 30);
        when(tick.getSymbol()).thenReturn("005930");
        when(tick.getPrice()).thenReturn(new BigDecimal("71000.00"));
        when(tick.getProvider()).thenReturn("kis-openapi");
        when(tick.getPriceTime()).thenReturn(priceTime);
        when(stockPriceTickRepository.findTop100BySymbolOrderByPriceTimeDesc("005930")).thenReturn(List.of(tick));

        var ticks = marketService.getPriceTicks("005930");

        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).symbol()).isEqualTo("005930");
        assertThat(ticks.get(0).price()).isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(ticks.get(0).provider()).isEqualTo("kis-openapi");
        assertThat(ticks.get(0).priceTime()).isEqualTo(priceTime);
    }

    @Test
    void getPriceTicks_lowercaseSymbol_normalizesToUppercase() {
        when(stockPriceTickRepository.findTop100BySymbolOrderByPriceTimeDesc("005930")).thenReturn(List.of());

        marketService.getPriceTicks(" 005930 ");

        verify(stockPriceTickRepository).findTop100BySymbolOrderByPriceTimeDesc("005930");
    }

    @Test
    void getPriceTicks_blankSymbol_throwsBadRequest() {
        assertThatThrownBy(() -> marketService.getPriceTicks(" "))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Symbol is required");
    }

    @Test
    void getOrderBook_openLimitOrders_returnsBidAndAskLevels() {
        StockOrderRepository.OrderBookLevelView bid = orderBookLevel("71000.00", 3L, 2L);
        StockOrderRepository.OrderBookLevelView ask = orderBookLevel("73000.00", 4L, 1L);
        when(stockInstrumentRepository.existsById("005930")).thenReturn(true);
        when(stockOrderRepository.findBidLevels(
                org.mockito.ArgumentMatchers.eq("005930"),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.OrderSide.BUY),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.OrderType.LIMIT),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(bid));
        when(stockOrderRepository.findAskLevels(
                org.mockito.ArgumentMatchers.eq("005930"),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.OrderSide.SELL),
                org.mockito.ArgumentMatchers.eq(stock.back.service.database.entity.OrderType.LIMIT),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(ask));

        var orderBook = marketService.getOrderBook("005930");

        assertThat(orderBook.symbol()).isEqualTo("005930");
        assertThat(orderBook.bids()).hasSize(1);
        assertThat(orderBook.bids().get(0).price()).isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(orderBook.bids().get(0).quantity()).isEqualTo(3L);
        assertThat(orderBook.asks().get(0).price()).isEqualByComparingTo(new BigDecimal("73000.00"));
        assertThat(orderBook.asks().get(0).orderCount()).isEqualTo(1L);
    }

    @Test
    void getOrderBook_lowercaseSymbol_normalizesToUppercase() {
        when(stockInstrumentRepository.existsById("005930")).thenReturn(true);
        when(stockOrderRepository.findBidLevels(
                org.mockito.ArgumentMatchers.eq("005930"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());
        when(stockOrderRepository.findAskLevels(
                org.mockito.ArgumentMatchers.eq("005930"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        var orderBook = marketService.getOrderBook(" 005930 ");

        assertThat(orderBook.symbol()).isEqualTo("005930");
        verify(stockInstrumentRepository).existsById("005930");
    }

    @Test
    void getOrderBook_unknownSymbol_throwsNotFound() {
        when(stockInstrumentRepository.existsById("UNKNOWN")).thenReturn(false);

        assertThatThrownBy(() -> marketService.getOrderBook("UNKNOWN"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown stock symbol");
    }

    @Test
    void getRankings_latestSnapshotDate_returnsRankedByReturnRate() {
        LocalDate latestSnapshotDate = LocalDate.of(2026, 6, 16);
        PortfolioSnapshot latestSnapshotMarker = org.mockito.Mockito.mock(PortfolioSnapshot.class);
        when(latestSnapshotMarker.getSnapshotDate()).thenReturn(latestSnapshotDate);
        PortfolioSnapshot first = snapshot("user-a", "10100000.00", "1.0000", latestSnapshotDate);
        PortfolioSnapshot second = snapshot("user-b", "10050000.00", "0.5000", latestSnapshotDate);
        when(portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()).thenReturn(Optional.of(latestSnapshotMarker));
        when(portfolioSnapshotRepository.findTop20BySnapshotDateOrderByReturnRateDesc(latestSnapshotDate))
                .thenReturn(List.of(first, second));

        var rankings = marketService.getRankings();

        assertThat(rankings).hasSize(2);
        assertThat(rankings.get(0).rank()).isEqualTo(1);
        assertThat(rankings.get(0).userKey()).isEqualTo("user-a");
        assertThat(rankings.get(0).displayName()).isEqualTo("투자자 user-a");
        assertThat(rankings.get(0).totalAsset()).isEqualByComparingTo(new BigDecimal("10100000.00"));
        assertThat(rankings.get(0).returnRate()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(rankings.get(0).snapshotDate()).isEqualTo(latestSnapshotDate);
        assertThat(rankings.get(1).rank()).isEqualTo(2);
        assertThat(rankings.get(1).userKey()).isEqualTo("user-b");
        assertThat(rankings.get(1).displayName()).isEqualTo("투자자 user-b");
    }

    @Test
    void getRankings_noSnapshots_returnsEmptyList() {
        when(portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()).thenReturn(Optional.empty());

        var rankings = marketService.getRankings();

        assertThat(rankings).isEmpty();
    }

    private StockOrderRepository.OrderBookLevelView orderBookLevel(String price, Long quantity, Long orderCount) {
        StockOrderRepository.OrderBookLevelView level = org.mockito.Mockito.mock(StockOrderRepository.OrderBookLevelView.class);
        when(level.getPrice()).thenReturn(new BigDecimal(price));
        when(level.getQuantity()).thenReturn(quantity);
        when(level.getOrderCount()).thenReturn(orderCount);
        return level;
    }

    private PortfolioSnapshot snapshot(String userKey, String totalAsset, String returnRate, LocalDate snapshotDate) {
        PortfolioSnapshot snapshot = org.mockito.Mockito.mock(PortfolioSnapshot.class);
        when(snapshot.getUserKey()).thenReturn(userKey);
        when(snapshot.getTotalAsset()).thenReturn(new BigDecimal(totalAsset));
        when(snapshot.getReturnRate()).thenReturn(new BigDecimal(returnRate));
        when(snapshot.getSnapshotDate()).thenReturn(snapshotDate);
        return snapshot;
    }
}
