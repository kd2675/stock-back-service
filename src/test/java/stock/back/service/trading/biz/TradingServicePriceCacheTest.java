package stock.back.service.trading.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.entity.StockOrder;
import stock.back.service.database.entity.StockVirtualMarketConfig;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockExecutionRepository;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.database.repository.StockVirtualMarketConfigRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.trading.vo.OrderRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingServicePriceCacheTest {

    @Mock
    private AccountService accountService;

    @Mock
    private StockInstrumentRepository stockInstrumentRepository;

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockVirtualMarketConfigRepository stockVirtualMarketConfigRepository;

    @Mock
    private StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private StockOrderRepository stockOrderRepository;

    @Mock
    private StockHoldingRepository stockHoldingRepository;

    @Mock
    private StockExecutionRepository stockExecutionRepository;

    @Mock
    private PortfolioSnapshotRepository portfolioSnapshotRepository;

    @Mock
    private StockPriceCacheService stockPriceCacheService;

    private TradingService tradingService;

    @BeforeEach
    void setUp() {
        tradingService = new TradingService(
                accountService,
                stockInstrumentRepository,
                stockOrderBookInstrumentRepository,
                stockVirtualMarketConfigRepository,
                stockOrderBookMarketConfigRepository,
                stockPriceRepository,
                stockOrderRepository,
                stockHoldingRepository,
                stockExecutionRepository,
                portfolioSnapshotRepository,
                stockPriceCacheService
        );
    }

    @Test
    void placeOrder_marketBuy_usesRedisCachedPriceForReservedCash() {
        StockAccount account = StockAccount.open("cache-order-user");
        account.depositCash(new BigDecimal("10000000.00"));
        ReflectionTestUtils.setField(account, "id", 101L);
        when(stockInstrumentRepository.existsById("005930")).thenReturn(true);
        when(stockVirtualMarketConfigRepository.findById("005930")).thenReturn(Optional.of(virtualMarketConfig("005930")));
        when(stockPriceCacheService.getCachedPrice("005930"))
                .thenReturn(Optional.of(new CachedStockPrice(new BigDecimal("72000.00"), "redis-cache")));
        when(accountService.requireAccountForUpdate("cache-order-user")).thenReturn(account);
        when(stockOrderRepository.save(any(StockOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = tradingService.placeOrder(
                "cache-order-user",
                new OrderRequest("005930", OrderSide.BUY, OrderType.MARKET, null, 2)
        );

        assertThat(response.reservedCash()).isEqualByComparingTo(new BigDecimal("144000.00"));
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9856000.00"));
        verify(stockPriceRepository, never()).findById("005930");
    }

    @Test
    void getPortfolio_holding_usesRedisCachedPriceForMarketValue() {
        StockAccount account = StockAccount.open("cache-portfolio-user");
        account.depositCash(new BigDecimal("10000000.00"));
        ReflectionTestUtils.setField(account, "id", 102L);
        StockHolding holding = holding(102L, "005930", 2, 0, "60000.00");
        when(accountService.requireAccount("cache-portfolio-user")).thenReturn(account);
        when(stockHoldingRepository.findByAccountIdOrderBySymbolAsc(102L))
                .thenReturn(List.of(holding));
        when(stockOrderRepository.sumReservedCashByAccountIdAndSideAndStatusIn(
                eq(102L),
                eq(OrderSide.BUY),
                anyList()
        )).thenReturn(BigDecimal.ZERO);
        when(stockOrderRepository.countByAccountIdAndStatusIn(
                eq(102L),
                anyList()
        )).thenReturn(0L);
        when(accountService.getNetCashFlow(102L)).thenReturn(new BigDecimal("10000000.00"));
        when(stockPriceCacheService.getCachedPrice("005930"))
                .thenReturn(Optional.of(new CachedStockPrice(new BigDecimal("75000.00"), "redis-cache")));

        var portfolio = tradingService.getPortfolio("cache-portfolio-user");

        assertThat(portfolio.holdings()).hasSize(1);
        assertThat(portfolio.holdings().get(0).currentPrice()).isEqualByComparingTo(new BigDecimal("75000.00"));
        assertThat(portfolio.holdings().get(0).marketValue()).isEqualByComparingTo(new BigDecimal("150000.00"));
        assertThat(portfolio.totalAsset()).isEqualByComparingTo(new BigDecimal("10150000.00"));
        verify(stockPriceRepository, never()).findById("005930");
    }

    private StockHolding holding(Long accountId, String symbol, long quantity, long reservedQuantity, String averagePrice) {
        StockHolding holding = BeanUtils.instantiateClass(StockHolding.class);
        ReflectionTestUtils.setField(holding, "accountId", accountId);
        ReflectionTestUtils.setField(holding, "symbol", symbol);
        ReflectionTestUtils.setField(holding, "quantity", quantity);
        ReflectionTestUtils.setField(holding, "reservedQuantity", reservedQuantity);
        ReflectionTestUtils.setField(holding, "averagePrice", new BigDecimal(averagePrice));
        ReflectionTestUtils.setField(holding, "updatedAt", LocalDateTime.now());
        return holding;
    }

    private StockVirtualMarketConfig virtualMarketConfig(String symbol) {
        StockVirtualMarketConfig config = BeanUtils.instantiateClass(StockVirtualMarketConfig.class);
        ReflectionTestUtils.setField(config, "symbol", symbol);
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "marketStatus", null);
        ReflectionTestUtils.setField(config, "updatedAt", LocalDateTime.now());
        return config;
    }
}
