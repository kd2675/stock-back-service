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

    @Column(name = "target_buy_quantity", nullable = false)
    private Long targetBuyQuantity;

    @Column(name = "target_sell_quantity", nullable = false)
    private Long targetSellQuantity;

    @Column(name = "target_holding_quantity", nullable = false)
    private Long targetHoldingQuantity;

    @Column(name = "inventory_band_quantity", nullable = false)
    private Long inventoryBandQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "buy_price_offset_direction", nullable = false, length = 10)
    private ListingAutoPriceDirection buyPriceOffsetDirection;

    @Enumerated(EnumType.STRING)
    @Column(name = "sell_price_offset_direction", nullable = false, length = 10)
    private ListingAutoPriceDirection sellPriceOffsetDirection;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockListingAutoAccountConfig defaults(String symbol, String userKey, String displayName, long issuedShares) {
        return defaults(symbol, userKey, displayName, issuedShares, LocalDateTime.now());
    }

    public static StockListingAutoAccountConfig defaults(
            String symbol,
            String userKey,
            String displayName,
            long issuedShares,
            LocalDateTime createdAt
    ) {
        int maxOrderQuantity = (int) Math.clamp(issuedShares / 1000, 1L, 100L);
        LocalDateTime now = createdAt == null ? LocalDateTime.now() : createdAt;
        StockListingAutoAccountConfig config = new StockListingAutoAccountConfig();
        config.symbol = symbol;
        config.userKey = userKey;
        config.displayName = displayName;
        config.enabled = true;
        config.positionSide = ListingAutoPosition.SELL_ONLY;
        config.maxOrderQuantity = maxOrderQuantity;
        config.orderTtlSeconds = 90;
        config.priceOffsetTicks = 3;
        config.targetBuyQuantity = 0L;
        config.targetSellQuantity = (long) maxOrderQuantity;
        config.targetHoldingQuantity = 0L;
        config.inventoryBandQuantity = 0L;
        config.buyPriceOffsetDirection = ListingAutoPriceDirection.DOWN;
        config.sellPriceOffsetDirection = ListingAutoPriceDirection.UP;
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
            Integer priceOffsetTicks,
            Long targetBuyQuantity,
            Long targetSellQuantity,
            Long targetHoldingQuantity,
            Long inventoryBandQuantity,
            ListingAutoPriceDirection buyPriceOffsetDirection,
            ListingAutoPriceDirection sellPriceOffsetDirection
    ) {
        update(
                displayName,
                enabled,
                positionSide,
                maxOrderQuantity,
                orderTtlSeconds,
                priceOffsetTicks,
                targetBuyQuantity,
                targetSellQuantity,
                targetHoldingQuantity,
                inventoryBandQuantity,
                buyPriceOffsetDirection,
                sellPriceOffsetDirection,
                LocalDateTime.now()
        );
    }

    public void update(
            String displayName,
            Boolean enabled,
            ListingAutoPosition positionSide,
            Integer maxOrderQuantity,
            Integer orderTtlSeconds,
            Integer priceOffsetTicks,
            Long targetBuyQuantity,
            Long targetSellQuantity,
            Long targetHoldingQuantity,
            Long inventoryBandQuantity,
            ListingAutoPriceDirection buyPriceOffsetDirection,
            ListingAutoPriceDirection sellPriceOffsetDirection,
            LocalDateTime updatedAt
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
        if (targetBuyQuantity != null) {
            this.targetBuyQuantity = targetBuyQuantity;
        }
        if (targetSellQuantity != null) {
            this.targetSellQuantity = targetSellQuantity;
        }
        if (targetHoldingQuantity != null) {
            this.targetHoldingQuantity = targetHoldingQuantity;
        }
        if (inventoryBandQuantity != null) {
            this.inventoryBandQuantity = inventoryBandQuantity;
        }
        if (buyPriceOffsetDirection != null) {
            this.buyPriceOffsetDirection = buyPriceOffsetDirection;
        }
        if (sellPriceOffsetDirection != null) {
            this.sellPriceOffsetDirection = sellPriceOffsetDirection;
        }
        initializeTargetForNewPosition(positionSide, targetBuyQuantity, targetSellQuantity);
        this.updatedAt = updatedAt == null ? LocalDateTime.now() : updatedAt;
    }

    private void initializeTargetForNewPosition(
            ListingAutoPosition requestedPosition,
            Long requestedBuyTarget,
            Long requestedSellTarget
    ) {
        if (requestedPosition == null) {
            return;
        }
        long defaultTarget = Math.max(1, maxOrderQuantity == null ? 1 : maxOrderQuantity);
        if ((requestedPosition == ListingAutoPosition.BUY_ONLY || requestedPosition == ListingAutoPosition.TWO_SIDED)
                && requestedBuyTarget == null
                && (targetBuyQuantity == null || targetBuyQuantity <= 0)) {
            targetBuyQuantity = defaultTarget;
        }
        if ((requestedPosition == ListingAutoPosition.SELL_ONLY || requestedPosition == ListingAutoPosition.TWO_SIDED)
                && requestedSellTarget == null
                && (targetSellQuantity == null || targetSellQuantity <= 0)) {
            targetSellQuantity = defaultTarget;
        }
    }
}
