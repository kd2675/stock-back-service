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
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_portfolio_snapshot_account_date",
                        columnNames = {"account_id", "snapshot_date"}
                ),
                @UniqueConstraint(
                        name = "uk_portfolio_snapshot_cycle_account",
                        columnNames = {"close_cycle_id", "account_id"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PortfolioSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "close_cycle_id")
    private Long closeCycleId;

    @Column(name = "close_run_id")
    private Long closeRunId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "total_asset", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAsset;

    @Column(name = "cash_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal cashBalance;

    @Column(name = "pending_subscription_asset", nullable = false, precision = 19, scale = 2)
    private BigDecimal pendingSubscriptionAsset;

    @Column(name = "market_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal marketValue;

    @Column(name = "holding_quantity")
    private Long holdingQuantity;

    @Column(name = "reserved_sell_quantity")
    private Long reservedSellQuantity;

    @Column(name = "holding_position_count")
    private Long holdingPositionCount;

    @Column(name = "net_contribution", precision = 19, scale = 2)
    private BigDecimal netContribution;

    @Column(name = "total_profit", precision = 19, scale = 2)
    private BigDecimal totalProfit;

    @Column(name = "return_rate", precision = 19, scale = 8)
    private BigDecimal returnRate;

    @Column(name = "return_rate_status", nullable = false, length = 40)
    private String returnRateStatus;

    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @Column(name = "calculation_version", length = 40)
    private String calculationVersion;

    @Column(name = "data_quality_status", length = 20)
    private String dataQualityStatus;

    @Column(name = "source_build_version", length = 100)
    private String sourceBuildVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
