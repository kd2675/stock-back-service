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
@Table(name = "stock_order_book_instrument")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockOrderBookInstrument {

    @Id
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "market", nullable = false, length = 20)
    private String market;

    @Column(name = "initial_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal initialPrice;

    @Column(name = "issued_shares", nullable = false)
    private Long issuedShares;

    @Column(name = "tradable_shares", nullable = false)
    private Long tradableShares;

    @Column(name = "tick_size", nullable = false, precision = 19, scale = 2)
    private BigDecimal tickSize;

    @Column(name = "price_limit_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal priceLimitRate;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockOrderBookInstrument listed(
            String symbol,
            String name,
            String market,
            BigDecimal initialPrice,
            long issuedShares
    ) {
        return listed(symbol, name, market, initialPrice, issuedShares, BigDecimal.ONE, BigDecimal.valueOf(30));
    }

    public static StockOrderBookInstrument listed(
            String symbol,
            String name,
            String market,
            BigDecimal initialPrice,
            long issuedShares,
            BigDecimal tickSize,
            BigDecimal priceLimitRate
    ) {
        LocalDateTime now = LocalDateTime.now();
        StockOrderBookInstrument instrument = new StockOrderBookInstrument();
        instrument.symbol = symbol;
        instrument.name = name;
        instrument.market = market;
        instrument.initialPrice = initialPrice;
        instrument.issuedShares = issuedShares;
        instrument.tradableShares = issuedShares;
        instrument.tickSize = tickSize == null ? BigDecimal.ONE : tickSize;
        instrument.priceLimitRate = priceLimitRate == null ? BigDecimal.valueOf(30) : priceLimitRate;
        instrument.enabled = true;
        instrument.createdAt = now;
        instrument.updatedAt = now;
        return instrument;
    }

    public void issueShares(long shares) {
        if (shares <= 0) {
            throw new IllegalArgumentException("Issued shares must be positive");
        }
        this.issuedShares = issuedShares + shares;
        this.tradableShares = tradableShares + shares;
        this.updatedAt = LocalDateTime.now();
    }

    public void applyStockSplit(int splitFrom, int splitTo) {
        if (splitFrom <= 0 || splitTo <= 0 || splitTo <= splitFrom || splitTo % splitFrom != 0) {
            throw new IllegalArgumentException("Only positive integer forward split ratios are supported");
        }
        int multiplier = splitTo / splitFrom;
        this.issuedShares = issuedShares * multiplier;
        this.tradableShares = tradableShares * multiplier;
        this.updatedAt = LocalDateTime.now();
    }

    public void delist() {
        this.enabled = false;
        this.updatedAt = LocalDateTime.now();
    }
}
