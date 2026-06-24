package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_stock_account_user_key", columnNames = "user_key"),
                @UniqueConstraint(name = "uk_stock_account_account_code", columnNames = "account_code")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_key", length = 64)
    private String userKey;

    @Column(name = "account_code", length = 32)
    private String accountCode;

    @Column(name = "recovery_code_hash", length = 128)
    private String recoveryCodeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, columnDefinition = "varchar(20) default 'ACTIVE'")
    private StockAccountStatus status;

    @Column(name = "cash_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal cashBalance;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "detached_at")
    private LocalDateTime detachedAt;

    @Column(name = "reconnected_at")
    private LocalDateTime reconnectedAt;

    @Column(name = "recovery_expires_at")
    private LocalDateTime recoveryExpiresAt;

    @Column(name = "purge_after")
    private LocalDateTime purgeAfter;

    @Column(name = "previous_user_key_hash", length = 128)
    private String previousUserKeyHash;

    @Transient
    private String issuedRecoveryCode;

    public static StockAccount open(String userKey) {
        return open(userKey, null, null, null);
    }

    public static StockAccount open(String userKey, String accountCode, String recoveryCodeHash, String issuedRecoveryCode) {
        StockAccount account = new StockAccount();
        account.userKey = userKey;
        account.accountCode = accountCode;
        account.recoveryCodeHash = recoveryCodeHash;
        account.status = StockAccountStatus.ACTIVE;
        account.cashBalance = BigDecimal.ZERO;
        account.createdAt = LocalDateTime.now();
        account.updatedAt = account.createdAt;
        account.issuedRecoveryCode = issuedRecoveryCode;
        return account;
    }

    public boolean isActive() {
        return status == null || status == StockAccountStatus.ACTIVE;
    }

    public boolean isDetached() {
        return status == StockAccountStatus.DETACHED;
    }

    public void assignAccountCodeIfMissing(String accountCode) {
        if (this.accountCode != null && !this.accountCode.isBlank()) {
            return;
        }
        this.accountCode = accountCode;
        this.updatedAt = LocalDateTime.now();
    }

    public void issueRecoveryCode(String recoveryCodeHash, String issuedRecoveryCode) {
        this.recoveryCodeHash = recoveryCodeHash;
        this.issuedRecoveryCode = issuedRecoveryCode;
        this.updatedAt = LocalDateTime.now();
    }

    public void detach(String previousUserKeyHash, String recoveryCodeHash, String issuedRecoveryCode, LocalDateTime recoveryExpiresAt, LocalDateTime purgeAfter) {
        LocalDateTime now = LocalDateTime.now();
        this.previousUserKeyHash = previousUserKeyHash;
        this.userKey = null;
        this.recoveryCodeHash = recoveryCodeHash;
        this.issuedRecoveryCode = issuedRecoveryCode;
        this.status = StockAccountStatus.DETACHED;
        this.detachedAt = now;
        this.reconnectedAt = null;
        this.recoveryExpiresAt = recoveryExpiresAt;
        this.purgeAfter = purgeAfter;
        this.updatedAt = now;
    }

    public void reconnect(String userKey, String recoveryCodeHash, String issuedRecoveryCode) {
        LocalDateTime now = LocalDateTime.now();
        this.userKey = userKey;
        this.recoveryCodeHash = recoveryCodeHash;
        this.issuedRecoveryCode = issuedRecoveryCode;
        this.status = StockAccountStatus.ACTIVE;
        this.detachedAt = null;
        this.reconnectedAt = now;
        this.recoveryExpiresAt = null;
        this.purgeAfter = null;
        this.updatedAt = now;
    }

    public void close() {
        this.status = StockAccountStatus.CLOSED;
        this.userKey = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void reserveCash(BigDecimal amount) {
        this.cashBalance = this.cashBalance.subtract(amount);
        this.updatedAt = LocalDateTime.now();
    }

    public void releaseCash(BigDecimal amount) {
        this.cashBalance = this.cashBalance.add(amount);
        this.updatedAt = LocalDateTime.now();
    }

    public void depositCash(BigDecimal amount) {
        this.cashBalance = this.cashBalance.add(amount);
        this.updatedAt = LocalDateTime.now();
    }

    public boolean withdrawCash(BigDecimal amount) {
        if (this.cashBalance.compareTo(amount) < 0) {
            return false;
        }
        this.cashBalance = this.cashBalance.subtract(amount);
        this.updatedAt = LocalDateTime.now();
        return true;
    }
}
