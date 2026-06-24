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
@Table(name = "stock_order_book_market_config")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockOrderBookMarketConfig {

    @Id
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_status", nullable = false, length = 20)
    private MarketSessionStatus marketStatus;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockOrderBookMarketConfig enabled(String symbol) {
        StockOrderBookMarketConfig config = new StockOrderBookMarketConfig();
        config.symbol = symbol;
        config.enabled = true;
        config.marketStatus = MarketSessionStatus.OPEN;
        config.updatedAt = LocalDateTime.now();
        return config;
    }

    public void updateStatus(Boolean enabled, MarketSessionStatus marketStatus) {
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (marketStatus != null) {
            this.marketStatus = marketStatus;
        }
        this.updatedAt = LocalDateTime.now();
    }
}
