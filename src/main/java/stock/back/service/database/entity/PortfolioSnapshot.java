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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "portfolio_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_portfolio_snapshot_user_date", columnNames = {"user_key", "snapshot_date"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PortfolioSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_key", nullable = false, length = 64)
    private String userKey;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "total_asset", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAsset;

    @Column(name = "cash_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal cashBalance;

    @Column(name = "market_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal marketValue;

    @Column(name = "return_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal returnRate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
