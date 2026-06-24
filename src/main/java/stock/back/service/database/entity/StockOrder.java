package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "stock_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_order_client_order_id", columnNames = "client_order_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_order_id", nullable = false, length = 64)
    private String clientOrderId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 30)
    private MarketType marketType;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 10)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "limit_price", precision = 19, scale = 2)
    private BigDecimal limitPrice;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "filled_quantity", nullable = false)
    private Long filledQuantity;

    @Column(name = "average_fill_price", precision = 19, scale = 2)
    private BigDecimal averageFillPrice;

    @Column(name = "reserved_cash", nullable = false, precision = 19, scale = 2)
    private BigDecimal reservedCash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockOrder pending(
            String clientOrderId,
            Long accountId,
            String symbol,
            MarketType marketType,
            OrderSide side,
            OrderType orderType,
            BigDecimal limitPrice,
            long quantity,
            BigDecimal reservedCash
    ) {
        StockOrder order = new StockOrder();
        order.clientOrderId = clientOrderId;
        order.accountId = accountId;
        order.symbol = symbol;
        order.marketType = marketType == null ? MarketType.VIRTUAL_PRICE : marketType;
        order.side = side;
        order.orderType = orderType;
        order.status = OrderStatus.PENDING;
        order.limitPrice = limitPrice;
        order.quantity = quantity;
        order.filledQuantity = 0L;
        order.reservedCash = reservedCash;
        order.createdAt = LocalDateTime.now();
        order.updatedAt = order.createdAt;
        return order;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
        this.reservedCash = BigDecimal.ZERO;
        this.updatedAt = LocalDateTime.now();
    }

    public void amendLimitOrder(long quantity, BigDecimal limitPrice, BigDecimal reservedCash) {
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.reservedCash = reservedCash;
        this.updatedAt = LocalDateTime.now();
    }

    public void reduceOpenQuantity(long quantityToCancel, BigDecimal reservedCash) {
        this.quantity = this.quantity - quantityToCancel;
        this.reservedCash = reservedCash;
        this.updatedAt = LocalDateTime.now();
    }
}
