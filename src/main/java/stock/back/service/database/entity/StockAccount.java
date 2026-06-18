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
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "stock_account",
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_account_user_key", columnNames = "user_key")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_key", nullable = false, length = 64)
    private String userKey;

    @Column(name = "cash_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal cashBalance;

    @Column(name = "initial_cash", nullable = false, precision = 19, scale = 2)
    private BigDecimal initialCash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockAccount open(String userKey, BigDecimal initialCash) {
        StockAccount account = new StockAccount();
        account.userKey = userKey;
        account.cashBalance = initialCash;
        account.initialCash = initialCash;
        account.createdAt = LocalDateTime.now();
        account.updatedAt = account.createdAt;
        return account;
    }

    public void reserveCash(BigDecimal amount) {
        this.cashBalance = this.cashBalance.subtract(amount);
        this.updatedAt = LocalDateTime.now();
    }

    public void releaseCash(BigDecimal amount) {
        this.cashBalance = this.cashBalance.add(amount);
        this.updatedAt = LocalDateTime.now();
    }
}
