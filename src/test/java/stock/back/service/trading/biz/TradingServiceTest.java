package stock.back.service.trading.biz;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.StockInstrument;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderRepository;
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
    private JdbcTemplate jdbcTemplate;

    @Test
    void placeOrder_buyLimit_reservesCashAndCreatesPendingOrder() {
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
        var first = tradingService.placeOrder(
                "user-idempotent-buy",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 2, "idem-buy-001")
        );

        var second = tradingService.placeOrder(
                "user-idempotent-buy",
                new OrderRequest("005930", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("71000"), 3, "idem-buy-001")
        );

        var account = stockAccountRepository.findByUserKey("user-idempotent-buy").orElseThrow();
        long pendingOrderCount = stockOrderRepository.countByUserKeyAndStatusIn(
                "user-idempotent-buy",
                java.util.List.of(OrderStatus.PENDING)
        );
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9860000.00"));
        assertThat(pendingOrderCount).isEqualTo(1L);
    }

    @Test
    void placeOrder_sameClientOrderIdForDifferentUser_throwsConflictWithoutOpeningAccount() {
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
            long pendingOrderCount = stockOrderRepository.countByUserKeyAndStatusIn(
                    "user-concurrent-buy",
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
    void placeOrder_marketBuy_ignoresSubmittedLimitPrice() {
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
        stockInstrumentRepository.save(StockInstrument.listed("123456", "테스트종목", "KOSPI"));

        var response = tradingService.placeOrder(
                "user-limit-without-price",
                new OrderRequest("123456", OrderSide.BUY, OrderType.LIMIT, new BigDecimal("70000"), 2)
        );

        var account = stockAccountRepository.findByUserKey("user-limit-without-price").orElseThrow();
        var order = stockOrderRepository.findById(response.id()).orElseThrow();
        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("9860000.00"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getReservedCash()).isEqualByComparingTo(new BigDecimal("140000.00"));
    }

    @Test
    void placeOrder_marketBuyWithoutCurrentPrice_throwsNotFoundWithoutOpeningAccount() {
        stockInstrumentRepository.save(StockInstrument.listed("234567", "테스트종목2", "KOSPI"));

        assertThatThrownBy(() -> tradingService.placeOrder(
                "user-market-without-price",
                new OrderRequest("234567", OrderSide.BUY, OrderType.MARKET, null, 1)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Price not found: 234567");

        assertThat(stockAccountRepository.findByUserKey("user-market-without-price")).isEmpty();
    }

    @Test
    void placeOrder_marketSellWithoutCurrentPrice_acceptsForOrderBookMatching() {
        stockInstrumentRepository.save(StockInstrument.listed("345678", "테스트종목3", "KOSPI"));
        insertHolding("user-market-sell-without-price", "345678", 2, 0, "50000.00");

        var response = tradingService.placeOrder(
                "user-market-sell-without-price",
                new OrderRequest("345678", OrderSide.SELL, OrderType.MARKET, null, 1)
        );

        var order = stockOrderRepository.findById(response.id()).orElseThrow();
        var holding = stockHoldingRepository.findByUserKeyAndSymbol("user-market-sell-without-price", "345678")
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

        var holding = stockHoldingRepository.findByUserKeyAndSymbol("user-reserve", "005930").orElseThrow();
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

        var holding = stockHoldingRepository.findByUserKeyAndSymbol("user-cancel-sell", "005930").orElseThrow();
        var cancelledOrder = stockOrderRepository.findById(order.id()).orElseThrow();
        assertThat(holding.getReservedQuantity()).isZero();
        assertThat(holding.getAvailableQuantity()).isEqualTo(5L);
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrder_buyPending_releasesReservedCashOnlyOnce() {
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

        var holding = stockHoldingRepository.findByUserKeyAndSymbol("user-cancel-partial-sell", "005930").orElseThrow();
        var cancelledOrder = stockOrderRepository.findById(orderId).orElseThrow();
        assertThat(holding.getReservedQuantity()).isZero();
        assertThat(holding.getAvailableQuantity()).isEqualTo(5L);
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void getPortfolioSnapshots_existingSnapshots_returnsLatestSnapshotsFirst() {
        tradingService.getPortfolio("user-snapshot");
        insertPortfolioSnapshot("user-snapshot", "2026-06-16", "10000000.00", "10000000.00", "0.00", "0.0000");
        insertPortfolioSnapshot("user-snapshot", "2026-06-17", "10100000.00", "9900000.00", "200000.00", "1.0000");

        var snapshots = tradingService.getPortfolioSnapshots("user-snapshot");

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).snapshotDate()).hasToString("2026-06-17");
        assertThat(snapshots.get(0).totalAsset()).isEqualByComparingTo(new BigDecimal("10100000.00"));
        assertThat(snapshots.get(1).snapshotDate()).hasToString("2026-06-16");
    }

    private void insertHolding(String userKey, String symbol, long quantity, long reservedQuantity, String averagePrice) {
        jdbcTemplate.update(
                """
                insert into stock_holding(user_key, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                userKey,
                symbol,
                quantity,
                reservedQuantity,
                new BigDecimal(averagePrice),
                LocalDateTime.now()
        );
    }

    private void insertAccount(String userKey, String cashBalance, String initialCash) {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, initial_cash, created_at, updated_at)
                values (?, ?, ?, ?, ?)
                """,
                userKey,
                new BigDecimal(cashBalance),
                new BigDecimal(initialCash),
                LocalDateTime.now(),
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
        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(user_key, snapshot_date, total_asset, cash_balance, market_value, return_rate, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                userKey,
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
        LocalDateTime createdAt = LocalDateTime.now();
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, user_key, symbol, side, order_type, status, limit_price,
                  quantity, filled_quantity, average_fill_price, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                clientOrderId,
                userKey,
                symbol,
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
}
