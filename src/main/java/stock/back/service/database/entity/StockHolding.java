package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "stock_holding",
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_holding_account_symbol", columnNames = {"account_id", "symbol"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "reserved_quantity", nullable = false)
    private Long reservedQuantity;

    @Column(name = "average_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal averagePrice;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public long getAvailableQuantity() {
        long reserved = reservedQuantity == null ? 0L : reservedQuantity;
        return quantity - reserved;
    }

    public void reserveQuantity(long quantityToReserve) {
        reserveQuantity(quantityToReserve, LocalDateTime.now());
    }

    public void reserveQuantity(long quantityToReserve, LocalDateTime updatedAt) {
        if (quantityToReserve <= 0) {
            throw new IllegalArgumentException("Reserved quantity must be positive");
        }
        if (getAvailableQuantity() < quantityToReserve) {
            throw new IllegalArgumentException("Not enough available holding quantity");
        }
        this.reservedQuantity = (reservedQuantity == null ? 0L : reservedQuantity) + quantityToReserve;
        this.updatedAt = updatedAt == null ? LocalDateTime.now() : updatedAt;
    }

    public void releaseReservedQuantity(long quantityToRelease) {
        releaseReservedQuantity(quantityToRelease, LocalDateTime.now());
    }

    public void releaseReservedQuantity(long quantityToRelease, LocalDateTime updatedAt) {
        if (quantityToRelease <= 0) {
            return;
        }
        long reserved = reservedQuantity == null ? 0L : reservedQuantity;
        this.reservedQuantity = Math.max(0L, reserved - quantityToRelease);
        this.updatedAt = updatedAt == null ? LocalDateTime.now() : updatedAt;
    }
}
