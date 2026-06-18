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
@Table(name = "stock_instrument")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockInstrument {

    @Id
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "market", nullable = false, length = 20)
    private String market;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static StockInstrument listed(String symbol, String name, String market) {
        StockInstrument instrument = new StockInstrument();
        instrument.symbol = symbol;
        instrument.name = name;
        instrument.market = market;
        instrument.enabled = true;
        instrument.createdAt = LocalDateTime.now();
        return instrument;
    }
}
