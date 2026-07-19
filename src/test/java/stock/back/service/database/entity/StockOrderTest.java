package stock.back.service.database.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockOrderTest {

    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 7, 20, 9, 0);
    private static final LocalDateTime AMENDED_AT = RECEIVED_AT.plusMinutes(1);

    @Test
    void amendLimitOrder_priceChanged_resetsTimePriority() {
        StockOrder order = pendingOrder(10, "100.00");

        order.amendLimitOrder(10, new BigDecimal("101.00"), new BigDecimal("1010.00"), AMENDED_AT);

        assertThat(order.getCreatedAt()).isEqualTo(AMENDED_AT);
    }

    @Test
    void amendLimitOrder_quantityIncreased_resetsTimePriority() {
        StockOrder order = pendingOrder(10, "100.00");

        order.amendLimitOrder(11, new BigDecimal("100.00"), new BigDecimal("1100.00"), AMENDED_AT);

        assertThat(order.getCreatedAt()).isEqualTo(AMENDED_AT);
    }

    @Test
    void amendLimitOrder_samePriceQuantityReduced_keepsTimePriority() {
        StockOrder order = pendingOrder(10, "100.00");

        order.amendLimitOrder(9, new BigDecimal("100.00"), new BigDecimal("900.00"), AMENDED_AT);

        assertThat(order.getCreatedAt()).isEqualTo(RECEIVED_AT);
    }

    private StockOrder pendingOrder(long quantity, String limitPrice) {
        BigDecimal price = new BigDecimal(limitPrice);
        return StockOrder.pending(
                "test-order",
                1L,
                "TEST",
                MarketType.ORDER_BOOK,
                OrderSide.BUY,
                OrderType.LIMIT,
                price,
                quantity,
                price.multiply(BigDecimal.valueOf(quantity)),
                RECEIVED_AT
        );
    }
}
