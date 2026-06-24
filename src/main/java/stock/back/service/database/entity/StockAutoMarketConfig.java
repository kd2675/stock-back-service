package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "stock_auto_market_config")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAutoMarketConfig {

    @Id
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "intensity", nullable = false)
    private Integer intensity;

    @Column(name = "max_order_quantity", nullable = false)
    private Integer maxOrderQuantity;

    @Column(name = "order_ttl_seconds", nullable = false)
    private Integer orderTtlSeconds;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockAutoMarketConfig defaults(String symbol) {
        StockAutoMarketConfig config = new StockAutoMarketConfig();
        config.symbol = symbol;
        config.enabled = true;
        config.intensity = 5;
        config.maxOrderQuantity = 4;
        config.orderTtlSeconds = 15;
        config.updatedAt = LocalDateTime.now();
        return config;
    }

    public void update(Boolean enabled, Integer intensity, Integer maxOrderQuantity, Integer orderTtlSeconds) {
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (intensity != null) {
            this.intensity = intensity;
        }
        if (maxOrderQuantity != null) {
            this.maxOrderQuantity = maxOrderQuantity;
        }
        if (orderTtlSeconds != null) {
            this.orderTtlSeconds = orderTtlSeconds;
        }
        this.updatedAt = LocalDateTime.now();
    }
}
