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

    @Column(name = "primary_price_pressure_bias", nullable = false)
    private Integer primaryPricePressureBias;

    @Column(name = "primary_asset_preference_pressure_bias", nullable = false)
    private Integer primaryAssetPreferencePressureBias;

    @Column(name = "primary_volatility_pressure_bias", nullable = false)
    private Integer primaryVolatilityPressureBias;

    @Column(name = "primary_liquidity_pressure_bias", nullable = false)
    private Integer primaryLiquidityPressureBias;

    @Column(name = "primary_execution_aggression_pressure_bias", nullable = false)
    private Integer primaryExecutionAggressionPressureBias;

    @Column(name = "secondary_price_pressure_bias", nullable = false)
    private Integer secondaryPricePressureBias;

    @Column(name = "secondary_asset_preference_pressure_bias", nullable = false)
    private Integer secondaryAssetPreferencePressureBias;

    @Column(name = "secondary_volatility_pressure_bias", nullable = false)
    private Integer secondaryVolatilityPressureBias;

    @Column(name = "secondary_liquidity_pressure_bias", nullable = false)
    private Integer secondaryLiquidityPressureBias;

    @Column(name = "secondary_execution_aggression_pressure_bias", nullable = false)
    private Integer secondaryExecutionAggressionPressureBias;

    @Column(name = "max_order_quantity", nullable = false)
    private Integer maxOrderQuantity;

    @Column(name = "order_ttl_seconds", nullable = false)
    private Integer orderTtlSeconds;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockAutoMarketConfig defaults(String symbol) {
        return defaults(symbol, LocalDateTime.now());
    }

    public static StockAutoMarketConfig defaults(String symbol, LocalDateTime updatedAt) {
        StockAutoMarketConfig config = new StockAutoMarketConfig();
        config.symbol = symbol;
        config.enabled = true;
        config.primaryPricePressureBias = 0;
        config.primaryAssetPreferencePressureBias = 0;
        config.primaryVolatilityPressureBias = 0;
        config.primaryLiquidityPressureBias = 0;
        config.primaryExecutionAggressionPressureBias = 0;
        config.secondaryPricePressureBias = 0;
        config.secondaryAssetPreferencePressureBias = 0;
        config.secondaryVolatilityPressureBias = 0;
        config.secondaryLiquidityPressureBias = 0;
        config.secondaryExecutionAggressionPressureBias = 0;
        config.maxOrderQuantity = 4;
        config.orderTtlSeconds = 15;
        config.updatedAt = updatedAt == null ? LocalDateTime.now() : updatedAt;
        return config;
    }

    public void update(Boolean enabled, Integer maxOrderQuantity, Integer orderTtlSeconds) {
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (maxOrderQuantity != null) {
            this.maxOrderQuantity = maxOrderQuantity;
        }
        if (orderTtlSeconds != null) {
            this.orderTtlSeconds = orderTtlSeconds;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void updatePrimaryDistributionBias(
            Integer price,
            Integer assetPreference,
            Integer volatility,
            Integer liquidity,
            Integer executionAggression
    ) {
        primaryPricePressureBias = valueOrCurrent(price, primaryPricePressureBias);
        primaryAssetPreferencePressureBias = valueOrCurrent(assetPreference, primaryAssetPreferencePressureBias);
        primaryVolatilityPressureBias = valueOrCurrent(volatility, primaryVolatilityPressureBias);
        primaryLiquidityPressureBias = valueOrCurrent(liquidity, primaryLiquidityPressureBias);
        primaryExecutionAggressionPressureBias = valueOrCurrent(executionAggression, primaryExecutionAggressionPressureBias);
    }

    public void updateSecondaryDistributionBias(
            Integer price,
            Integer assetPreference,
            Integer volatility,
            Integer liquidity,
            Integer executionAggression
    ) {
        secondaryPricePressureBias = valueOrCurrent(price, secondaryPricePressureBias);
        secondaryAssetPreferencePressureBias = valueOrCurrent(assetPreference, secondaryAssetPreferencePressureBias);
        secondaryVolatilityPressureBias = valueOrCurrent(volatility, secondaryVolatilityPressureBias);
        secondaryLiquidityPressureBias = valueOrCurrent(liquidity, secondaryLiquidityPressureBias);
        secondaryExecutionAggressionPressureBias = valueOrCurrent(executionAggression, secondaryExecutionAggressionPressureBias);
    }

    private int valueOrCurrent(Integer value, Integer current) {
        return value == null ? (current == null ? 0 : current) : value;
    }
}
