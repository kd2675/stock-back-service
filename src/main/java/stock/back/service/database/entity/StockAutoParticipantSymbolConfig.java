package stock.back.service.database.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "stock_auto_participant_symbol_config")
@IdClass(StockAutoParticipantSymbolConfigId.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAutoParticipantSymbolConfig {

    @Id
    @Column(name = "user_key", nullable = false, length = 64)
    private String userKey;

    @Id
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "intensity", nullable = false)
    private Integer intensity;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockAutoParticipantSymbolConfig defaults(String userKey, String symbol, int intensity) {
        StockAutoParticipantSymbolConfig config = new StockAutoParticipantSymbolConfig();
        config.userKey = userKey;
        config.symbol = symbol;
        config.enabled = true;
        config.intensity = intensity;
        config.updatedAt = LocalDateTime.now();
        return config;
    }

    public void update(Boolean enabled, Integer intensity) {
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (intensity != null) {
            this.intensity = intensity;
        }
        this.updatedAt = LocalDateTime.now();
    }
}
