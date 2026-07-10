package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "stock_corporate_action_entitlement",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_stock_corporate_action_entitlement_action_account",
                columnNames = {"action_id", "account_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCorporateActionEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_id", nullable = false)
    private Long actionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "share_quantity")
    private Long shareQuantity;

    @Column(name = "cash_amount", precision = 19, scale = 2)
    private BigDecimal cashAmount;

    @Column(name = "subscribed_share_quantity")
    private Long subscribedShareQuantity;

    @Column(name = "subscribed_cash_amount", precision = 19, scale = 2)
    private BigDecimal subscribedCashAmount;

    @Column(name = "holding_snapshot_run_id")
    private Long holdingSnapshotRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StockCorporateActionEntitlementStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "subscribed_at")
    private LocalDateTime subscribedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    public void subscribe(long shareQuantity, BigDecimal cashAmount, LocalDateTime subscribedAt) {
        this.subscribedShareQuantity = shareQuantity;
        this.subscribedCashAmount = cashAmount;
        this.status = StockCorporateActionEntitlementStatus.SUBSCRIBED;
        this.subscribedAt = subscribedAt;
    }
}
