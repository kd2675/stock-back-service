package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "stock_listing_auto_account_config")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockListingAutoAccountConfig {

    @Id
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "user_key", nullable = false, length = 64)
    private String userKey;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "position_side", nullable = false, length = 20)
    private ListingAutoPosition positionSide;

    @Column(name = "max_order_quantity", nullable = false)
    private Integer maxOrderQuantity;

    @Column(name = "order_ttl_seconds", nullable = false)
    private Integer orderTtlSeconds;

    @Column(name = "price_offset_ticks", nullable = false)
    private Integer priceOffsetTicks;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockListingAutoAccountConfig defaults(String symbol, String userKey, String displayName, long issuedShares) {
        int maxOrderQuantity = (int) Math.max(1, Math.min(100, issuedShares / 1000));
        LocalDateTime now = LocalDateTime.now();
        StockListingAutoAccountConfig config = new StockListingAutoAccountConfig();
        config.symbol = symbol;
        config.userKey = userKey;
        config.displayName = displayName;
        config.enabled = true;
        config.positionSide = ListingAutoPosition.SELL_ONLY;
        config.maxOrderQuantity = maxOrderQuantity;
        config.orderTtlSeconds = 30;
        config.priceOffsetTicks = 3;
        config.createdAt = now;
        config.updatedAt = now;
        return config;
    }

    public void update(
            String displayName,
            Boolean enabled,
            ListingAutoPosition positionSide,
            Integer maxOrderQuantity,
            Integer orderTtlSeconds,
            Integer priceOffsetTicks
    ) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (positionSide != null) {
            this.positionSide = positionSide;
        }
        if (maxOrderQuantity != null) {
            this.maxOrderQuantity = maxOrderQuantity;
        }
        if (orderTtlSeconds != null) {
            this.orderTtlSeconds = orderTtlSeconds;
        }
        if (priceOffsetTicks != null) {
            this.priceOffsetTicks = priceOffsetTicks;
        }
        this.updatedAt = LocalDateTime.now();
    }
}
