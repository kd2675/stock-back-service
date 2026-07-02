package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "stock_price")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockPrice {

    @Id
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "current_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "previous_close", nullable = false, precision = 19, scale = 2)
    private BigDecimal previousClose;

    @Column(name = "price_time", nullable = false)
    private LocalDateTime priceTime;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    public static StockPrice initial(String symbol, BigDecimal price) {
        return initial(symbol, price, LocalDateTime.now());
    }

    public static StockPrice initial(String symbol, BigDecimal price, LocalDateTime priceTime) {
        StockPrice stockPrice = new StockPrice();
        stockPrice.symbol = symbol;
        stockPrice.currentPrice = price;
        stockPrice.previousClose = price;
        stockPrice.priceTime = priceTime == null ? LocalDateTime.now() : priceTime;
        stockPrice.provider = "initial-listing";
        return stockPrice;
    }

    public void update(BigDecimal currentPrice, String provider) {
        update(currentPrice, provider, LocalDateTime.now());
    }

    public void update(BigDecimal currentPrice, String provider, LocalDateTime priceTime) {
        this.currentPrice = currentPrice;
        this.priceTime = priceTime == null ? LocalDateTime.now() : priceTime;
        this.provider = provider;
    }
}
