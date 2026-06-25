package stock.back.service.trading.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.StockInstrument;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.trading.vo.OrderAmendRequest;
import stock.back.service.trading.vo.OrderCancelRequest;
import stock.back.service.trading.vo.OrderRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TradingServiceTest {

    @Autowired
    private TradingService tradingService;

    @Autowired
    private StockAccountRepository stockAccountRepository;

    @Autowired
    private StockOrderRepository stockOrderRepository;

    @Autowired
    private StockHoldingRepository stockHoldingRepository;

    @Autowired
    private StockInstrumentRepository stockInstrumentRepository;

    @Autowired
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureDefaultVirtualMarketData() {
        if (!stockInstrumentRepository.existsById("005930")) {
            stockInstrumentRepository.save(StockInstrument.listed("005930", "삼성전자", "KOSPI"));
        }
        Long priceCount = jdbcTemplate.queryForObject(
                "select count(*) from stock_price where symbol = ?",
                Long.class,
                "005930"
        );
        if (priceCount == null || priceCount == 0) {
            jdbcTemplate.update(
                    """
                    insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                    values (?, ?, ?, ?, 'test-seed')
                    """,
                    "005930",
                    new BigDecimal("72400.00"),
                    new BigDecimal("72400.00"),
                    LocalDateTime.now()
            );
        }
        jdbcTemplate.update(
                """
                merge into stock_virtual_market_config(symbol, enabled, market_status, updated_at)
                key(symbol)
                values ('005930', true, 'OPEN', ?)
                """,
                LocalDateTime.now()
        );
    }

    @Test
    void placeOrder_buyLimit_reservesCashAndCreatesPendingOrder() {
        insertAccountIfAbsent("user-buy");

        var response = tradingService.placeOrder(
                "user-buy",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 2)
        );

        var account = stockAccountRepository.findByUserKey("user-buy").orElseThrow();
        var order = stockOrderRepository.findById(response.id()).orElseThrow();

        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9860000.00"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void placeOrder_sameClientOrderIdForSameUser_returnsExistingOrderWithoutDoubleReservation() {
        insertAccountIfAbsent("user-idempotent-buy");

        var first = tradingService.placeOrder(
                "user-idempotent-buy",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 2, "idem-buy-001")
        );

        var second = tradingService.placeOrder(
                "user-idempotent-buy",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("71000"), 3, "idem-buy-001")
        );

        var account = stockAccountRepository.findByUserKey("user-idempotent-buy").orElseThrow();
        long pendingOrderCount = stockOrderRepository.countByAccountIdAndStatusIn(
                account.getId(),
                java.util.List.of(OrderStatus.PENDING)
        );
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9860000.00"));
        assertThat(pendingOrderCount).isEqualTo(1L);
    }

    @Test
    void placeOrder_sameClientOrderIdForDifferentUser_throwsConflictWithoutOpeningAccount() {
        insertAccountIfAbsent("user-idempotent-owner");

        tradingService.placeOrder(
                "user-idempotent-owner",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 1, "idem-shared-001")
        );

        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-idempotent-other",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 1, "idem-shared-001")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Client order id already exists");

        assertThat(stockAccountRepository.findByUserKey("user-idempotent-other")).isEmpty();
    }

    @Test
    void placeOrder_invalidClientOrderId_throwsBadRequestWithoutOpeningAccount() {
        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-invalid-client-order-id",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 1, "invalid order id")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Client order id contains invalid characters");

        assertThat(stockAccountRepository.findByUserKey("user-invalid-client-order-id")).isEmpty();
    }

    @Test
    void placeOrder_concurrentBuyOrders_reservesCashWithAccountLock() throws Exception {
        insertAccount("user-concurrent-buy", "100000.00", "100000.00");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> placeConcurrentBuyOrder(start));
            Future<Boolean> second = executor.submit(() -> placeConcurrentBuyOrder(start));

            start.countDown();

            int successCount = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            var account = stockAccountRepository.findByUserKey("user-concurrent-buy").orElseThrow();
            long pendingOrderCount = stockOrderRepository.countByAccountIdAndStatusIn(
                    account.getId(),
                    java.util.List.of(OrderStatus.PENDING)
            );

            assertThat(successCount).isEqualTo(1);
            assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("30000.00"));
            assertThat(pendingOrderCount).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void getPortfolio_pendingBuyOrder_includesReservedCashInTotalAsset() {
        insertAccountIfAbsent("user-pending-buy-asset");

        tradingService.placeOrder(
                "user-pending-buy-asset",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 2)
        );

        var portfolio = tradingService.getPortfolio("user-pending-buy-asset");

        assertThat(portfolio.account().cashBalance()).isEqualByComparingTo(new BigDecimal("9860000.00"));
        assertThat(portfolio.reservedBuyCash()).isEqualByComparingTo(new BigDecimal("140000.00"));
        assertThat(portfolio.totalAsset()).isEqualByComparingTo(new BigDecimal("10000000.00"));
        assertThat(portfolio.pendingOrderCount()).isEqualTo(1L);
    }

    @Test
    void getPortfolio_holdingWithoutCurrentPrice_usesAveragePriceAsFallback() {
        insertHolding("user-price-fallback", "123456", 2, 0, "50000.00");

        var portfolio = tradingService.getPortfolio("user-price-fallback");

        assertThat(portfolio.holdings()).hasSize(1);
        assertThat(portfolio.holdings().get(0).currentPrice()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(portfolio.holdings().get(0).marketValue()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(portfolio.totalAsset()).isEqualByComparingTo(new BigDecimal("10100000.00"));
    }

    @Test
    void getPortfolio_dividendPaymentImprovesReturnWithoutIncreasingPrincipal() {
        insertAccount("user-dividend-return", "10100000.00", "10000000.00");
        insertCashFlow("user-dividend-return", "100000.00", "DIVIDEND_PAYMENT");

        var portfolio = tradingService.getPortfolio("user-dividend-return");

        assertThat(portfolio.totalAsset()).isEqualByComparingTo(new BigDecimal("10100000.00"));
        assertThat(portfolio.returnRate()).isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    @Test
    void getHoldings_returnsHoldingsWithCurrentValuation() {
        insertHolding("user-holdings-api", "123456", 3, 1, "50000.00");

        var holdings = tradingService.getHoldings("user-holdings-api");

        assertThat(holdings).hasSize(1);
        assertThat(holdings.get(0).symbol()).isEqualTo("123456");
        assertThat(holdings.get(0).quantity()).isEqualTo(3L);
        assertThat(holdings.get(0).reservedQuantity()).isEqualTo(1L);
        assertThat(holdings.get(0).availableQuantity()).isEqualTo(2L);
        assertThat(holdings.get(0).currentPrice()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(holdings.get(0).marketValue()).isEqualByComparingTo(new BigDecimal("150000.00"));
    }

    @Test
    void placeOrder_sellWithoutHolding_throwsConflict() {
        insertAccountIfAbsent("user-sell");

        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-sell",
                new OrderRequest("005930", OrderSide.SELL, OrderType.LIMIT, new BigDecimal("80000"), 1)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Not enough holding quantity");
    }

    @Test
    void placeOrder_nullSymbol_throwsBadRequestWithoutOpeningAccount() {
        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-null-symbol",
                new OrderRequest(null, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 1)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Symbol is required");

        assertThat(stockAccountRepository.findByUserKey("user-null-symbol")).isEmpty();
    }

    @Test
    void placeOrder_nullSide_throwsBadRequestWithoutOpeningAccount() {
        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-null-side",
                new OrderRequest("005930", null, OrderType.LIMIT, new BigDecimal("70000"), 1)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Order side is required");

        assertThat(stockAccountRepository.findByUserKey("user-null-side")).isEmpty();
    }

    @Test
    void placeOrder_nullOrderType_throwsBadRequestWithoutOpeningAccount() {
        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-null-type",
                new OrderRequest("005930", OrderSide.BUY, null, new BigDecimal("70000"), 1)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Order type is required");

        assertThat(stockAccountRepository.findByUserKey("user-null-type")).isEmpty();
    }

    @Test
    void placeOrder_limitWithoutPrice_throwsBadRequestWithoutOpeningAccount() {
        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-null-limit-price",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, null, 1)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Limit price is required for limit orders");

        assertThat(stockAccountRepository.findByUserKey("user-null-limit-price")).isEmpty();
    }

    @Test
    void placeOrder_nonPositiveLimitPrice_throwsBadRequestWithoutOpeningAccount() {
        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-negative-limit-price",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, BigDecimal.ZERO, 1)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Limit price must be positive");

        assertThat(stockAccountRepository.findByUserKey("user-negative-limit-price")).isEmpty();
    }

    @Test
    void placeOrder_limitPriceOutsideDailyPriceLimit_throwsBadRequestWithoutOpeningAccount() {
        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-price-limit-over",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100000"), 1)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Limit price must be between");

        assertThat(stockAccountRepository.findByUserKey("user-price-limit-over")).isEmpty();
    }

    @Test
    void placeOrder_orderBookLimitPriceNotMatchingTickSize_throwsBadRequestWithoutOpeningAccount() {
        stockOrderBookInstrumentRepository.save(StockOrderBookInstrument.listed(
                "TICK5",
                "호가단위 테스트",
                "ORDERBOOK",
                new BigDecimal("70000"),
                100000L,
                new BigDecimal("5.00"),
                new BigDecimal("30.00")
        ));
        insertOrderBookMarketConfig("TICK5", "OPEN");

        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-tick-size-invalid",
                new OrderRequest("TICK5", MarketType.ORDER_BOOK, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70003"), 1, null)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Limit price must match tick size 5");

        assertThat(stockAccountRepository.findByUserKey("user-tick-size-invalid")).isEmpty();
    }

    @Test
    void placeOrder_closedVirtualMarket_throwsConflictWithoutOpeningAccount() {
        jdbcTemplate.update(
                "update stock_virtual_market_config set market_status = 'CLOSED' where symbol = '005930'"
        );

        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-closed-market",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 1)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Market is not open: 005930");

        assertThat(stockAccountRepository.findByUserKey("user-closed-market")).isEmpty();
    }

    @Test
    void placeOrder_haltedOrderBookMarket_throwsConflictWithoutOpeningAccount() {
        stockOrderBookInstrumentRepository.save(StockOrderBookInstrument.listed("HALT1", "거래정지 테스트", "ORDERBOOK", new BigDecimal("70000"), 100000L));
        insertOrderBookMarketConfig("HALT1", "HALTED");

        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-halted-orderbook",
                new OrderRequest("HALT1", MarketType.ORDER_BOOK, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 1, null)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Market is not open: HALT1");

        assertThat(stockAccountRepository.findByUserKey("user-halted-orderbook")).isEmpty();
    }

    @Test
    void placeOrder_marketBuy_ignoresSubmittedLimitPrice() {
        insertAccountIfAbsent("user-market-positive-limit-price");

        var response = tradingService.placeOrder(
                "user-market-positive-limit-price",
                new OrderRequest("005930", OrderSide.BUY, OrderType.MARKET, new BigDecimal("1"), 1)
        );

        var order = stockOrderRepository.findById(response.id()).orElseThrow();
        assertThat(order.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(order.getLimitPrice()).isNull();
    }

    @Test
    void placeOrder_marketBuy_ignoresNonPositiveSubmittedLimitPrice() {
        insertAccountIfAbsent("user-market-zero-limit-price");

        var response = tradingService.placeOrder(
                "user-market-zero-limit-price",
                new OrderRequest("005930", OrderSide.BUY, OrderType.MARKET, BigDecimal.ZERO, 1)
        );

        var order = stockOrderRepository.findById(response.id()).orElseThrow();
        assertThat(order.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(order.getLimitPrice()).isNull();
        assertThat(order.getReservedCash()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void placeOrder_buyLimitWithoutCurrentPrice_acceptsForOrderBookMatching() {
        stockOrderBookInstrumentRepository.save(StockOrderBookInstrument.listed("123456", "테스트종목", "ORDERBOOK", new BigDecimal("70000"), 100000L));
        insertOrderBookMarketConfig("123456", "OPEN");
        insertAccountIfAbsent("user-limit-without-price");

        var response = tradingService.placeOrder(
                "user-limit-without-price",
                new OrderRequest("123456", MarketType.ORDER_BOOK, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 2, null)
        );

        var account = stockAccountRepository.findByUserKey("user-limit-without-price").orElseThrow();
        var order = stockOrderRepository.findById(response.id()).orElseThrow();
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9860000.00"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getReservedCash()).isEqualByComparingTo(new BigDecimal("140000.00"));
    }

    @Test
    void placeOrder_marketBuyWithoutCurrentPrice_throwsNotFoundWithoutOpeningAccount() {
        stockOrderBookInstrumentRepository.save(StockOrderBookInstrument.listed("234567", "테스트종목2", "ORDERBOOK", new BigDecimal("70000"), 100000L));
        insertOrderBookMarketConfig("234567", "OPEN");

        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-market-without-price",
                new OrderRequest("234567", MarketType.ORDER_BOOK, OrderSide.BUY, OrderType.MARKET, null, 1, null)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Price not found: 234567");

        assertThat(stockAccountRepository.findByUserKey("user-market-without-price")).isEmpty();
    }

    @Test
    void placeOrder_marketSellWithoutCurrentPrice_acceptsForOrderBookMatching() {
        stockOrderBookInstrumentRepository.save(StockOrderBookInstrument.listed("345678", "테스트종목3", "ORDERBOOK", new BigDecimal("70000"), 100000L));
        insertOrderBookMarketConfig("345678", "OPEN");
        insertHolding("user-market-sell-without-price", "345678", 2, 0, "50000.00");

        var response = tradingService.placeOrder(
                "user-market-sell-without-price",
                new OrderRequest("345678", MarketType.ORDER_BOOK, OrderSide.SELL, OrderType.MARKET, null, 1, null)
        );

        var order = stockOrderRepository.findById(response.id()).orElseThrow();
        var account = stockAccountRepository.findByUserKey("user-market-sell-without-price").orElseThrow();
        var holding = stockHoldingRepository.findByAccountIdAndSymbol(account.getId(), "345678")
                .orElseThrow();
        assertThat(order.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(order.getLimitPrice()).isNull();
        assertThat(order.getReservedCash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(holding.getReservedQuantity()).isEqualTo(1L);
        assertThat(holding.getAvailableQuantity()).isEqualTo(1L);
    }

    @Test
    void placeOrder_sellLimit_reservesOnlyAvailableHoldingQuantity() {
        insertHolding("user-reserve", "005930", 5, 0, "60000.00");

        tradingService.placeOrder(
                "user-reserve",
                new OrderRequest("005930", OrderSide.SELL, OrderType.LIMIT, new BigDecimal("80000"), 3)
        );

        var account = stockAccountRepository.findByUserKey("user-reserve").orElseThrow();
        var holding = stockHoldingRepository.findByAccountIdAndSymbol(account.getId(), "005930").orElseThrow();
        assertThat(holding.getQuantity()).isEqualTo(5L);
        assertThat(holding.getReservedQuantity()).isEqualTo(3L);
        assertThat(holding.getAvailableQuantity()).isEqualTo(2L);
        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-reserve",
                new OrderRequest("005930", OrderSide.SELL, OrderType.LIMIT, new BigDecimal("81000"), 3)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Not enough holding quantity");
    }

    @Test
    void cancelOrder_sellPending_releasesReservedHoldingQuantity() {
        insertHolding("user-cancel-sell", "005930", 5, 0, "60000.00");
        var order = tradingService.placeOrder(
                "user-cancel-sell",
                new OrderRequest("005930", OrderSide.SELL, OrderType.LIMIT, new BigDecimal("80000"), 2)
        );

        tradingService.cancelOrder("user-cancel-sell", order.id());

        var account = stockAccountRepository.findByUserKey("user-cancel-sell").orElseThrow();
        var holding = stockHoldingRepository.findByAccountIdAndSymbol(account.getId(), "005930").orElseThrow();
        var cancelledOrder = stockOrderRepository.findById(order.id()).orElseThrow();
        assertThat(holding.getReservedQuantity()).isZero();
        assertThat(holding.getAvailableQuantity()).isEqualTo(5L);
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrder_buyPending_releasesReservedCashOnlyOnce() {
        insertAccountIfAbsent("user-cancel-buy");

        var order = tradingService.placeOrder(
                "user-cancel-buy",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 2)
        );

        tradingService.cancelOrder("user-cancel-buy", order.id());

        var account = stockAccountRepository.findByUserKey("user-cancel-buy").orElseThrow();
        var cancelledOrder = stockOrderRepository.findById(order.id()).orElseThrow();
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("10000000.00"));
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelledOrder.getReservedCash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> tradingService.cancelOrder("user-cancel-buy", order.id()))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Only pending orders can be cancelled");
        assertThat(stockAccountRepository.findByUserKey("user-cancel-buy").orElseThrow().getCashBalance())
                .isEqualByComparingTo(new BigDecimal("10000000.00"));
    }

    @Test
    void cancelOrder_buyPartiallyFilled_releasesRemainingReservedCashOnly() {
        insertAccount("user-cancel-partial-buy", "9790000.00", "10000000.00");
        Long orderId = insertOrder(
                "partial-buy-cancel",
                "user-cancel-partial-buy",
                "005930",
                "BUY",
                "LIMIT",
                "PARTIALLY_FILLED",
                "70000.00",
                3,
                1,
                "70000.00",
                "140000.00"
        );

        tradingService.cancelOrder("user-cancel-partial-buy", orderId);

        var account = stockAccountRepository.findByUserKey("user-cancel-partial-buy").orElseThrow();
        var cancelledOrder = stockOrderRepository.findById(orderId).orElseThrow();
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9930000.00"));
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelledOrder.getReservedCash()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void cancelOrder_sellPartiallyFilled_releasesRemainingReservedHoldingOnly() {
        insertHolding("user-cancel-partial-sell", "005930", 5, 2, "60000.00");
        Long orderId = insertOrder(
                "partial-sell-cancel",
                "user-cancel-partial-sell",
                "005930",
                "SELL",
                "LIMIT",
                "PARTIALLY_FILLED",
                "80000.00",
                3,
                1,
                "80000.00",
                "0.00"
        );

        tradingService.cancelOrder("user-cancel-partial-sell", orderId);

        var account = stockAccountRepository.findByUserKey("user-cancel-partial-sell").orElseThrow();
        var holding = stockHoldingRepository.findByAccountIdAndSymbol(account.getId(), "005930").orElseThrow();
        var cancelledOrder = stockOrderRepository.findById(orderId).orElseThrow();
        assertThat(holding.getReservedQuantity()).isZero();
        assertThat(holding.getAvailableQuantity()).isEqualTo(5L);
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void amendOrder_buyLimit_recalculatesReservedCash() {
        insertAccountIfAbsent("user-amend-buy");

        var order = tradingService.placeOrder(
                "user-amend-buy",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 2)
        );

        var amended = tradingService.amendOrder(
                "user-amend-buy",
                order.id(),
                new OrderAmendRequest(3L, new BigDecimal("71000.00"))
        );

        var account = stockAccountRepository.findByUserKey("user-amend-buy").orElseThrow();
        var savedOrder = stockOrderRepository.findById(order.id()).orElseThrow();
        assertThat(amended.quantity()).isEqualTo(3L);
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9787000.00"));
        assertThat(savedOrder.getReservedCash()).isEqualByComparingTo(new BigDecimal("213000.00"));
        assertThat(savedOrder.getLimitPrice()).isEqualByComparingTo(new BigDecimal("71000.00"));
    }

    @Test
    void amendOrder_limitPriceOutsideDailyPriceLimit_throwsBadRequestWithoutChangingReservation() {
        insertAccountIfAbsent("user-amend-price-limit");

        var order = tradingService.placeOrder(
                "user-amend-price-limit",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 2)
        );

        assertThatThrownBy(() -> tradingService.amendOrder(
                "user-amend-price-limit",
                order.id(),
                new OrderAmendRequest(2L, new BigDecimal("100000.00"))
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Limit price must be between");

        var account = stockAccountRepository.findByUserKey("user-amend-price-limit").orElseThrow();
        var savedOrder = stockOrderRepository.findById(order.id()).orElseThrow();
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9860000.00"));
        assertThat(savedOrder.getReservedCash()).isEqualByComparingTo(new BigDecimal("140000.00"));
        assertThat(savedOrder.getLimitPrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
    }

    @Test
    void amendOrder_sellLimit_recalculatesReservedQuantity() {
        insertHolding("user-amend-sell", "005930", 5, 0, "60000.00");
        var order = tradingService.placeOrder(
                "user-amend-sell",
                new OrderRequest("005930", OrderSide.SELL, OrderType.LIMIT, new BigDecimal("80000"), 3)
        );

        tradingService.amendOrder(
                "user-amend-sell",
                order.id(),
                new OrderAmendRequest(2L, new BigDecimal("81000.00"))
        );

        var account = stockAccountRepository.findByUserKey("user-amend-sell").orElseThrow();
        var holding = stockHoldingRepository.findByAccountIdAndSymbol(account.getId(), "005930").orElseThrow();
        var savedOrder = stockOrderRepository.findById(order.id()).orElseThrow();
        assertThat(holding.getReservedQuantity()).isEqualTo(2L);
        assertThat(holding.getAvailableQuantity()).isEqualTo(3L);
        assertThat(savedOrder.getQuantity()).isEqualTo(2L);
        assertThat(savedOrder.getLimitPrice()).isEqualByComparingTo(new BigDecimal("81000.00"));
    }

    @Test
    void cancelOrderPartially_buyLimit_releasesCancelledQuantityCash() {
        insertAccountIfAbsent("user-partial-cancel-buy");

        var order = tradingService.placeOrder(
                "user-partial-cancel-buy",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 3)
        );

        var partiallyCancelled = tradingService.cancelOrderPartially(
                "user-partial-cancel-buy",
                order.id(),
                new OrderCancelRequest(1L)
        );

        var account = stockAccountRepository.findByUserKey("user-partial-cancel-buy").orElseThrow();
        var savedOrder = stockOrderRepository.findById(order.id()).orElseThrow();
        assertThat(partiallyCancelled.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9860000.00"));
        assertThat(savedOrder.getQuantity()).isEqualTo(2L);
        assertThat(savedOrder.getReservedCash()).isEqualByComparingTo(new BigDecimal("140000.00"));
    }

    @Test
    void cancelOrderPartially_sellLimit_releasesCancelledQuantityHolding() {
        insertHolding("user-partial-cancel-sell", "005930", 5, 0, "60000.00");
        var order = tradingService.placeOrder(
                "user-partial-cancel-sell",
                new OrderRequest("005930", OrderSide.SELL, OrderType.LIMIT, new BigDecimal("80000"), 3)
        );

        tradingService.cancelOrderPartially(
                "user-partial-cancel-sell",
                order.id(),
                new OrderCancelRequest(1L)
        );

        var account = stockAccountRepository.findByUserKey("user-partial-cancel-sell").orElseThrow();
        var holding = stockHoldingRepository.findByAccountIdAndSymbol(account.getId(), "005930").orElseThrow();
        var savedOrder = stockOrderRepository.findById(order.id()).orElseThrow();
        assertThat(holding.getReservedQuantity()).isEqualTo(2L);
        assertThat(holding.getAvailableQuantity()).isEqualTo(3L);
        assertThat(savedOrder.getQuantity()).isEqualTo(2L);
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void getPortfolioSnapshots_existingSnapshots_returnsLatestSnapshotsFirst() {
        insertPortfolioSnapshot("user-snapshot", "2026-06-16", "10000000.00", "10000000.00", "0.00", "0.0000");
        insertPortfolioSnapshot("user-snapshot", "2026-06-17", "10100000.00", "9900000.00", "200000.00", "1.0000");

        var snapshots = tradingService.getPortfolioSnapshots("user-snapshot");

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).snapshotDate()).hasToString("2026-06-17");
        assertThat(snapshots.get(0).totalAsset()).isEqualByComparingTo(new BigDecimal("10100000.00"));
        assertThat(snapshots.get(1).snapshotDate()).hasToString("2026-06-16");
    }

    @Test
    void getOrders_marketTypeFilter_returnsMatchingOrdersBeforeTop50Limit() {
        LocalDateTime oldOrderTime = LocalDateTime.now().minusDays(1);
        insertOrder(
                "old-order-book-order",
                "user-order-filter",
                "005930",
                "ORDER_BOOK",
                "BUY",
                "LIMIT",
                "PENDING",
                "70000.00",
                1,
                0,
                null,
                "70000.00",
                oldOrderTime
        );
        for (int index = 0; index < 55; index++) {
            insertOrder(
                    "recent-virtual-order-" + index,
                    "user-order-filter",
                    "005930",
                    "VIRTUAL_PRICE",
                    "BUY",
                    "LIMIT",
                    "PENDING",
                    "70000.00",
                    1,
                    0,
                    null,
                    "70000.00",
                    LocalDateTime.now().plusSeconds(index)
            );
        }

        var orders = tradingService.getOrders("user-order-filter", MarketType.ORDER_BOOK);

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).marketType()).isEqualTo(MarketType.ORDER_BOOK);
        assertThat(orders.get(0).clientOrderId()).isEqualTo("old-order-book-order");
    }

    @Test
    void getExecutions_sourceFilter_returnsMatchingExecutionsBeforeTop50Limit() {
        LocalDateTime oldExecutionTime = LocalDateTime.now().minusDays(1);
        insertExecution(
                "user-execution-filter",
                "005930",
                "BUY",
                1,
                "70000.00",
                "0.00",
                "0.00",
                "70000.00",
                null,
                "INTERNAL_ORDER_BOOK",
                oldExecutionTime
        );
        for (int index = 0; index < 55; index++) {
            insertExecution(
                    "user-execution-filter",
                    "005930",
                    "BUY",
                    1,
                    "70000.00",
                    "0.00",
                    "0.00",
                    "70000.00",
                    null,
                    "VIRTUAL_MARKET_PRICE",
                    LocalDateTime.now().plusSeconds(index)
            );
        }

        var executions = tradingService.getExecutions("user-execution-filter", ExecutionSource.INTERNAL_ORDER_BOOK);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).source()).isEqualTo(ExecutionSource.INTERNAL_ORDER_BOOK);
    }

    @Test
    void getProfitSummary_existingExecutionsAndHolding_returnsLedgerAndValuationSummary() {
        insertHolding("user-profit-summary", "005930", 2, 0, "70000.00");
        insertExecution("user-profit-summary", "005930", "BUY", 1, "100000.00", "100.00", "0.00", "100100.00", null);
        insertExecution("user-profit-summary", "005930", "SELL", 1, "80000.00", "80.00", "160.00", "79760.00", "9760.00");
        insertExecution("user-profit-summary", "005930", "SELL", 1, "40000.00", "40.00", "80.00", "39880.00", "-120.00");

        var summary = tradingService.getProfitSummary("user-profit-summary");

        assertThat(summary.realizedProfit()).isEqualByComparingTo(new BigDecimal("9640.00"));
        assertThat(summary.unrealizedProfit()).isEqualByComparingTo(new BigDecimal("4800.00"));
        assertThat(summary.totalProfit()).isEqualByComparingTo(new BigDecimal("14440.00"));
        assertThat(summary.totalFeeAmount()).isEqualByComparingTo(new BigDecimal("220.00"));
        assertThat(summary.totalTaxAmount()).isEqualByComparingTo(new BigDecimal("240.00"));
        assertThat(summary.buyGrossAmount()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(summary.sellGrossAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
        assertThat(summary.buyNetAmount()).isEqualByComparingTo(new BigDecimal("100100.00"));
        assertThat(summary.sellNetAmount()).isEqualByComparingTo(new BigDecimal("119640.00"));
        assertThat(summary.netCashFlow()).isEqualByComparingTo(new BigDecimal("19540.00"));
        assertThat(summary.executionCount()).isEqualTo(3L);
    }

    @Test
    void getProfitSummary_newUser_returnsZeroSummary() {
        var summary = tradingService.getProfitSummary("user-profit-summary-empty");

        assertThat(summary.realizedProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.unrealizedProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.totalProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.totalFeeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.totalTaxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.executionCount()).isZero();
        assertThat(stockAccountRepository.findByUserKey("user-profit-summary-empty")).isEmpty();
    }

    private void insertHolding(String userKey, String symbol, long quantity, long reservedQuantity, String averagePrice) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                accountId,
                symbol,
                quantity,
                reservedQuantity,
                new BigDecimal(averagePrice),
                LocalDateTime.now()
        );
    }

    private void insertExecution(
            String userKey,
            String symbol,
            String side,
            long quantity,
            String grossAmount,
            String feeAmount,
            String taxAmount,
            String netAmount,
            String realizedProfit
    ) {
        insertExecution(
                userKey,
                symbol,
                side,
                quantity,
                grossAmount,
                feeAmount,
                taxAmount,
                netAmount,
                realizedProfit,
                "VIRTUAL_MARKET_PRICE",
                LocalDateTime.now()
        );
    }

    private void insertExecution(
            String userKey,
            String symbol,
            String side,
            long quantity,
            String grossAmount,
            String feeAmount,
            String taxAmount,
            String netAmount,
            String realizedProfit,
            String source,
            LocalDateTime executedAt
    ) {
        BigDecimal gross = new BigDecimal(grossAmount);
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_execution(
                  order_id, account_id, symbol, side, quantity, price, gross_amount, fee_amount,
                  tax_amount, net_amount, realized_profit, source, executed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                1L,
                accountId,
                symbol,
                side,
                quantity,
                gross.divide(BigDecimal.valueOf(quantity)),
                gross,
                new BigDecimal(feeAmount),
                new BigDecimal(taxAmount),
                new BigDecimal(netAmount),
                realizedProfit == null ? null : new BigDecimal(realizedProfit),
                source,
                executedAt
        );
    }

    private void insertAccount(String userKey, String cashBalance, String openingGrantAmount) {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values (?, ?, ?, ?)
                """,
                userKey,
                new BigDecimal(cashBalance),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        insertCashFlow(userKey, openingGrantAmount, "OPENING_GRANT");
    }

    private void insertCashFlow(String userKey, String amount, String reason) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', ?, ?, 'SYSTEM', ?
                from stock_account
                where user_key = ?
                """,
                new BigDecimal(amount),
                reason,
                LocalDateTime.now(),
                userKey
        );
    }

    private void insertOrderBookMarketConfig(String symbol, String marketStatus) {
        jdbcTemplate.update(
                """
                merge into stock_order_book_market_config(symbol, enabled, market_status, updated_at)
                key(symbol)
                values (?, true, ?, ?)
                """,
                symbol,
                marketStatus,
                LocalDateTime.now()
        );
    }

    private boolean placeConcurrentBuyOrder(CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            tradingService.placeOrder(
                    "user-concurrent-buy",
                    new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 1)
            );
            return true;
        } catch (StockException ex) {
            assertThat(ex.getMessage()).contains("Not enough cash balance");
            return false;
        }
    }

    private void insertPortfolioSnapshot(
            String userKey,
            String snapshotDate,
            String totalAsset,
            String cashBalance,
            String marketValue,
            String returnRate
    ) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(account_id, snapshot_date, total_asset, cash_balance, market_value, return_rate, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                accountId,
                java.time.LocalDate.parse(snapshotDate),
                new BigDecimal(totalAsset),
                new BigDecimal(cashBalance),
                new BigDecimal(marketValue),
                new BigDecimal(returnRate),
                LocalDateTime.now()
        );
    }

    private Long insertOrder(
            String clientOrderId,
            String userKey,
            String symbol,
            String side,
            String orderType,
            String status,
            String limitPrice,
            long quantity,
            long filledQuantity,
            String averageFillPrice,
            String reservedCash
    ) {
        return insertOrder(
                clientOrderId,
                userKey,
                symbol,
                "VIRTUAL_PRICE",
                side,
                orderType,
                status,
                limitPrice,
                quantity,
                filledQuantity,
                averageFillPrice,
                reservedCash,
                LocalDateTime.now()
        );
    }

    private Long insertOrder(
            String clientOrderId,
            String userKey,
            String symbol,
            String marketType,
            String side,
            String orderType,
            String status,
            String limitPrice,
            long quantity,
            long filledQuantity,
            String averageFillPrice,
            String reservedCash,
            LocalDateTime createdAt
    ) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
                  quantity, filled_quantity, average_fill_price, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                marketType,
                side,
                orderType,
                status,
                limitPrice == null ? null : new BigDecimal(limitPrice),
                quantity,
                filledQuantity,
                averageFillPrice == null ? null : new BigDecimal(averageFillPrice),
                new BigDecimal(reservedCash),
                createdAt,
                createdAt
        );
        return jdbcTemplate.queryForObject(
                "select id from stock_order where client_order_id = ?",
                Long.class,
                clientOrderId
        );
    }

    private Long accountIdFor(String userKey) {
        insertAccountIfAbsent(userKey);
        return jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                userKey
        );
    }

    private void insertAccountIfAbsent(String userKey) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from stock_account where user_key = ?",
                Long.class,
                userKey
        );
        if (count != null && count > 0) {
            return;
        }
        insertAccount(userKey, "10000000.00", "10000000.00");
    }
}
