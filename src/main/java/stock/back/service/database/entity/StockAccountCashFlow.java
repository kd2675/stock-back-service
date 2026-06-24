package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "stock_account_cash_flow")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAccountCashFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, length = 20)
    private StockAccountCashFlowType flowType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private StockAccountCashFlowReason reason;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static StockAccountCashFlow openingGrant(Long accountId, BigDecimal amount) {
        return create(accountId, StockAccountCashFlowType.DEPOSIT, amount, StockAccountCashFlowReason.OPENING_GRANT, "SYSTEM");
    }

    public static StockAccountCashFlow adminDeposit(Long accountId, BigDecimal amount, String createdBy) {
        return create(accountId, StockAccountCashFlowType.DEPOSIT, amount, StockAccountCashFlowReason.ADMIN_DEPOSIT, createdBy);
    }

    public static StockAccountCashFlow adminWithdraw(Long accountId, BigDecimal amount, String createdBy) {
        return create(accountId, StockAccountCashFlowType.WITHDRAW, amount, StockAccountCashFlowReason.ADMIN_WITHDRAW, createdBy);
    }

    private static StockAccountCashFlow create(
            Long accountId,
            StockAccountCashFlowType flowType,
            BigDecimal amount,
            StockAccountCashFlowReason reason,
            String createdBy
    ) {
        StockAccountCashFlow cashFlow = new StockAccountCashFlow();
        cashFlow.accountId = accountId;
        cashFlow.flowType = flowType;
        cashFlow.amount = amount;
        cashFlow.reason = reason;
        cashFlow.createdBy = createdBy;
        cashFlow.createdAt = LocalDateTime.now();
        return cashFlow;
    }
}
