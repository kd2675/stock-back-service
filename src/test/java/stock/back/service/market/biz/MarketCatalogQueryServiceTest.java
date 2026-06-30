package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.database.repository.StockPriceTickRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketCatalogQueryServiceTest {

    @Mock
    private StockInstrumentRepository stockInstrumentRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private StockPriceTickRepository stockPriceTickRepository;

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private PortfolioSnapshotRepository portfolioSnapshotRepository;

    @Mock
    private StockAccountRepository stockAccountRepository;

    @Mock
    private StockPriceCacheService stockPriceCacheService;

    private MarketCatalogQueryService service;

    @BeforeEach
    void setUp() {
        service = new MarketCatalogQueryService(
                stockInstrumentRepository,
                stockPriceRepository,
                stockPriceTickRepository,
                stockOrderBookInstrumentRepository,
                portfolioSnapshotRepository,
                stockAccountRepository,
                stockPriceCacheService
        );
    }

    @Test
    void getPrices_cachedPriceExists_usesCachePriceAndProvider() {
        when(stockPriceRepository.findVirtualMarketPrices())
                .thenReturn(List.of(StockPrice.initial("005930", new BigDecimal("70000.00"))));
        when(stockPriceCacheService.getCachedPrice("005930"))
                .thenReturn(Optional.of(new CachedStockPrice(new BigDecimal("71000.00"), "redis-cache")));

        var prices = service.getPrices();

        assertThat(prices).hasSize(1);
        assertThat(prices.get(0).currentPrice()).isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(prices.get(0).changeRate()).isEqualByComparingTo(new BigDecimal("1.4286"));
        assertThat(prices.get(0).provider()).isEqualTo("redis-cache");
    }

    @Test
    void getRankings_loadsAccountsInSingleBatch() {
        LocalDate latestSnapshotDate = LocalDate.of(2026, 6, 30);
        PortfolioSnapshot marker = mock(PortfolioSnapshot.class);
        PortfolioSnapshot first = snapshot(101L, "10100000.00", "1.0000", latestSnapshotDate);
        PortfolioSnapshot second = snapshot(102L, "10050000.00", "0.5000", latestSnapshotDate);
        StockAccount firstAccount = account(101L, "user-a");
        StockAccount secondAccount = account(102L, "user-b");
        when(marker.getSnapshotDate()).thenReturn(latestSnapshotDate);
        when(portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()).thenReturn(Optional.of(marker));
        when(portfolioSnapshotRepository.findTop20BySnapshotDateOrderByReturnRateDesc(latestSnapshotDate))
                .thenReturn(List.of(first, second));
        when(stockAccountRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(firstAccount, secondAccount));

        var rankings = service.getRankings();

        assertThat(rankings).hasSize(2);
        assertThat(rankings.get(0).userKey()).isEqualTo("user-a");
        assertThat(rankings.get(1).userKey()).isEqualTo("user-b");
        verify(stockAccountRepository).findAllById(List.of(101L, 102L));
        verify(stockAccountRepository, never()).findById(any());
    }

    private PortfolioSnapshot snapshot(Long accountId, String totalAsset, String returnRate, LocalDate snapshotDate) {
        PortfolioSnapshot snapshot = mock(PortfolioSnapshot.class);
        when(snapshot.getAccountId()).thenReturn(accountId);
        when(snapshot.getTotalAsset()).thenReturn(new BigDecimal(totalAsset));
        when(snapshot.getReturnRate()).thenReturn(new BigDecimal(returnRate));
        when(snapshot.getSnapshotDate()).thenReturn(snapshotDate);
        return snapshot;
    }

    private StockAccount account(Long id, String userKey) {
        StockAccount account = mock(StockAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getUserKey()).thenReturn(userKey);
        return account;
    }
}
