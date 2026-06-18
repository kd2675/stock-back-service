package stock.back.service.database.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StockOrderRepositoryOrderBookTest {

    @Autowired
    private StockOrderRepository stockOrderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_order");
    }

    @Test
    void findBidLevels_openLimitOrders_groupsRemainingQuantityByPrice() {
        insertOrder("buy-a", "user-a", "005930", "BUY", "LIMIT", "PENDING", "71000.00", 5, 2, "213000.00", 1);
        insertOrder("buy-b", "user-b", "005930", "BUY", "LIMIT", "PENDING", "71000.00", 4, 0, "284000.00", 2);
        insertOrder("buy-c", "user-c", "005930", "BUY", "LIMIT", "PARTIALLY_FILLED", "70000.00", 3, 1, "140000.00", 3);
        insertOrder("buy-filled", "user-d", "005930", "BUY", "LIMIT", "FILLED", "72000.00", 1, 1, "0.00", 4);
        insertOrder("buy-market", "user-e", "005930", "BUY", "MARKET", "PENDING", null, 10, 0, "710000.00", 5);
        insertOrder("buy-other-symbol", "user-f", "000660", "BUY", "LIMIT", "PENDING", "80000.00", 10, 0, "800000.00", 6);

        var levels = stockOrderRepository.findBidLevels(
                "005930",
                OrderSide.BUY,
                OrderType.LIMIT,
                List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED),
                PageRequest.of(0, 10)
        );

        assertThat(levels).hasSize(2);
        assertThat(levels.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(levels.get(0).getQuantity()).isEqualTo(7L);
        assertThat(levels.get(0).getOrderCount()).isEqualTo(2L);
        assertThat(levels.get(1).getPrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(levels.get(1).getQuantity()).isEqualTo(2L);
    }

    @Test
    void findAskLevels_openLimitOrders_ordersByLowestAskFirst() {
        insertOrder("sell-a", "user-a", "005930", "SELL", "LIMIT", "PENDING", "73000.00", 5, 1, "0.00", 1);
        insertOrder("sell-b", "user-b", "005930", "SELL", "LIMIT", "PENDING", "72000.00", 3, 0, "0.00", 2);
        insertOrder("sell-cancelled", "user-c", "005930", "SELL", "LIMIT", "CANCELLED", "71000.00", 9, 0, "0.00", 3);

        var levels = stockOrderRepository.findAskLevels(
                "005930",
                OrderSide.SELL,
                OrderType.LIMIT,
                List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED),
                PageRequest.of(0, 10)
        );

        assertThat(levels).hasSize(2);
        assertThat(levels.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("72000.00"));
        assertThat(levels.get(0).getQuantity()).isEqualTo(3L);
        assertThat(levels.get(1).getPrice()).isEqualByComparingTo(new BigDecimal("73000.00"));
        assertThat(levels.get(1).getQuantity()).isEqualTo(4L);
    }

    private void insertOrder(
            String clientOrderId,
            String userKey,
            String symbol,
            String side,
            String orderType,
            String status,
            String limitPrice,
            long quantity,
            long filledQuantity,
            String reservedCash,
            int secondsOffset
    ) {
        LocalDateTime createdAt = LocalDateTime.now().plusSeconds(secondsOffset);
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, user_key, symbol, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                new BigDecimal(reservedCash),
                createdAt,
                createdAt
        );
    }
}
