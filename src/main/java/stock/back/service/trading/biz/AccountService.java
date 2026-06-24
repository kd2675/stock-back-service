package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.trading.vo.AccountReconnectRequest;
import stock.back.service.trading.vo.AccountResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AccountService {

    private static final int RECOVERY_DAYS = 30;
    private static final int PURGE_DAYS = 90;
    private static final Pattern ACCOUNT_CODE_PATTERN = Pattern.compile("^[A-Z0-9-]{6,32}$");
    private static final char[] RECOVERY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final StockAccountRepository stockAccountRepository;
    private final StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private final StockHoldingRepository stockHoldingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${stock.trading.opening-grant-amount:10000000}")
    private BigDecimal openingGrantAmount;

    @Transactional
    public StockAccount getOrOpenAccount(String userKey) {
        validateUserKey(userKey);
        return stockAccountRepository.findByUserKeyAndStatus(userKey, StockAccountStatus.ACTIVE)
                .orElseGet(() -> openAccountAfterCreateRace(userKey));
    }

    @Transactional
    public StockAccount getOrOpenAccountForUpdate(String userKey) {
        validateUserKey(userKey);
        return stockAccountRepository.findByUserKeyAndStatusForUpdate(userKey, StockAccountStatus.ACTIVE)
                .orElseGet(() -> openAccountForUpdateAfterCreateRace(userKey));
    }

    @Transactional(readOnly = true)
    public Optional<StockAccount> findAccount(String userKey) {
        validateUserKey(userKey);
        return stockAccountRepository.findByUserKeyAndStatus(userKey, StockAccountStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public StockAccount requireAccount(String userKey) {
        return findAccount(userKey)
                .orElseThrow(() -> StockException.notFound("Account not found"));
    }

    @Transactional
    public StockAccount requireAccountForUpdate(String userKey) {
        validateUserKey(userKey);
        return stockAccountRepository.findByUserKeyAndStatusForUpdate(userKey, StockAccountStatus.ACTIVE)
                .orElseThrow(() -> StockException.notFound("Account not found"));
    }

    @Transactional(readOnly = true)
    public BigDecimal getNetCashFlow(Long accountId) {
        if (accountId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal netCashFlow = stockAccountCashFlowRepository.sumNetCashFlowByAccountId(accountId);
        return netCashFlow == null ? BigDecimal.ZERO : netCashFlow;
    }

    @Transactional
    public AccountResponse openAccount(String userKey) {
        validateUserKey(userKey);
        StockAccount account = stockAccountRepository.findByUserKeyAndStatusForUpdate(userKey, StockAccountStatus.ACTIVE)
                .map(this::issueRecoveryCredentials)
                .orElseGet(() -> openAccountAfterCreateRace(userKey));
        return toResponse(account);
    }

    @Transactional
    public AccountResponse detachAccount(String userKey) {
        StockAccount account = requireAccountForUpdate(userKey);
        cancelOpenOrdersForDetach(account);
        account.assignAccountCodeIfMissing(generateAccountCode());
        String recoveryCode = generateRecoveryCode();
        LocalDateTime now = LocalDateTime.now();
        account.detach(
                hashValue(userKey),
                hashRecoveryCode(recoveryCode),
                recoveryCode,
                now.plusDays(RECOVERY_DAYS),
                now.plusDays(PURGE_DAYS)
        );
        return toResponse(account);
    }

    @Transactional(noRollbackFor = StockException.class)
    public AccountResponse reconnectAccount(String userKey, AccountReconnectRequest request) {
        validateUserKey(userKey);
        if (findAccount(userKey).isPresent()) {
            throw StockException.conflict("Active account already exists");
        }
        String accountCode = normalizeAccountCode(request == null ? null : request.accountCode());
        String recoveryCode = normalizeRecoveryCode(request == null ? null : request.recoveryCode());
        StockAccount account = stockAccountRepository.findByAccountCodeForUpdate(accountCode)
                .orElseThrow(() -> StockException.notFound("Recoverable account not found"));
        if (account.isActive()) {
            throw StockException.conflict("Account is already connected");
        }
        if (!account.isDetached()) {
            throw StockException.notFound("Recoverable account not found");
        }
        LocalDateTime now = LocalDateTime.now();
        if (account.getPurgeAfter() != null && now.isAfter(account.getPurgeAfter())) {
            account.close();
            throw StockException.notFound("Account recovery period expired");
        }
        if (account.getRecoveryExpiresAt() != null && now.isAfter(account.getRecoveryExpiresAt())) {
            throw StockException.conflict("Account recovery code expired");
        }
        if (!matchesRecoveryCode(recoveryCode, account.getRecoveryCodeHash())) {
            throw StockException.unauthorized("Invalid recovery code");
        }
        String nextRecoveryCode = generateRecoveryCode();
        account.reconnect(userKey, hashRecoveryCode(nextRecoveryCode), nextRecoveryCode);
        return toResponse(account);
    }

    private void validateUserKey(String userKey) {
        if (userKey == null || userKey.isBlank()) {
            throw StockException.unauthorized("Login required");
        }
    }

    public AccountResponse toResponse(StockAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getUserKey(),
                account.getAccountCode(),
                account.getStatus() == null ? StockAccountStatus.ACTIVE.name() : account.getStatus().name(),
                account.getCashBalance(),
                account.getDetachedAt(),
                account.getReconnectedAt(),
                account.getRecoveryExpiresAt(),
                account.getPurgeAfter(),
                account.getIssuedRecoveryCode()
        );
    }

    private StockAccount openAccountAfterCreateRace(String userKey) {
        try {
            String recoveryCode = generateRecoveryCode();
            StockAccount account = stockAccountRepository.saveAndFlush(StockAccount.open(
                    userKey,
                    generateAccountCode(),
                    hashRecoveryCode(recoveryCode),
                    recoveryCode
            ));
            applyOpeningGrant(account);
            return account;
        } catch (DataIntegrityViolationException ex) {
            return stockAccountRepository.findByUserKeyAndStatus(userKey, StockAccountStatus.ACTIVE)
                    .map(this::issueRecoveryCredentials)
                    .orElseThrow(() -> ex);
        }
    }

    private StockAccount issueRecoveryCredentials(StockAccount account) {
        account.assignAccountCodeIfMissing(generateAccountCode());
        String recoveryCode = generateRecoveryCode();
        account.issueRecoveryCode(hashRecoveryCode(recoveryCode), recoveryCode);
        return account;
    }

    private StockAccount openAccountForUpdateAfterCreateRace(String userKey) {
        try {
            insertAccount(userKey);
        } catch (DataIntegrityViolationException ex) {
            return stockAccountRepository.findByUserKeyAndStatusForUpdate(userKey, StockAccountStatus.ACTIVE)
                    .orElseThrow(() -> ex);
        }
        return stockAccountRepository.findByUserKeyAndStatusForUpdate(userKey, StockAccountStatus.ACTIVE)
                .map(account -> {
                    applyOpeningGrant(account);
                    return account;
                })
                .orElseThrow(() -> StockException.notFound("Account not found after opening"));
    }

    private void insertAccount(String userKey) {
        LocalDateTime now = LocalDateTime.now();
        String recoveryCode = generateRecoveryCode();
        jdbcTemplate.update(
                """
                insert into stock_account(
                    user_key, account_code, recovery_code_hash, status,
                    cash_balance, created_at, updated_at
                )
                values (?, ?, ?, 'ACTIVE', 0.00, ?, ?)
                """,
                userKey,
                generateAccountCode(),
                hashRecoveryCode(recoveryCode),
                now,
                now
        );
    }

    private void applyOpeningGrant(StockAccount account) {
        if (openingGrantAmount == null || openingGrantAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        account.depositCash(openingGrantAmount);
        stockAccountCashFlowRepository.save(StockAccountCashFlow.openingGrant(account.getId(), openingGrantAmount));
    }

    private void cancelOpenOrdersForDetach(StockAccount account) {
        List<String> openStatuses = List.of(OrderStatus.PENDING.name(), OrderStatus.PARTIALLY_FILLED.name());
        BigDecimal reservedBuyCash = jdbcTemplate.queryForObject(
                """
                select coalesce(sum(reserved_cash), 0)
                  from stock_order
                 where account_id = ?
                   and side = ?
                   and status in (?, ?)
                """,
                BigDecimal.class,
                account.getId(),
                OrderSide.BUY.name(),
                openStatuses.get(0),
                openStatuses.get(1)
        );
        if (reservedBuyCash != null && reservedBuyCash.compareTo(BigDecimal.ZERO) > 0) {
            account.releaseCash(reservedBuyCash);
        }

        List<Map<String, Object>> sellReservations = jdbcTemplate.queryForList(
                """
                select symbol, coalesce(sum(quantity - filled_quantity), 0) as remaining_quantity
                  from stock_order
                 where account_id = ?
                   and side = ?
                   and status in (?, ?)
                 group by symbol
                """,
                account.getId(),
                OrderSide.SELL.name(),
                openStatuses.get(0),
                openStatuses.get(1)
        );
        for (Map<String, Object> row : sellReservations) {
            String symbol = String.valueOf(row.get("symbol"));
            long remainingQuantity = ((Number) row.get("remaining_quantity")).longValue();
            stockHoldingRepository.findByAccountIdAndSymbolForUpdate(account.getId(), symbol)
                    .ifPresent(holding -> holding.releaseReservedQuantity(remainingQuantity));
        }

        jdbcTemplate.update(
                """
                update stock_order
                   set status = 'CANCELLED',
                       reserved_cash = 0,
                       updated_at = ?
                 where account_id = ?
                   and status in (?, ?)
                """,
                LocalDateTime.now(),
                account.getId(),
                openStatuses.get(0),
                openStatuses.get(1)
        );
    }

    private String normalizeAccountCode(String accountCode) {
        if (accountCode == null || accountCode.isBlank()) {
            throw StockException.badRequest("Account code is required");
        }
        String normalized = accountCode.trim().toUpperCase(Locale.ROOT);
        if (!ACCOUNT_CODE_PATTERN.matcher(normalized).matches()) {
            throw StockException.badRequest("Account code contains invalid characters");
        }
        return normalized;
    }

    private String normalizeRecoveryCode(String recoveryCode) {
        if (recoveryCode == null || recoveryCode.isBlank()) {
            throw StockException.badRequest("Recovery code is required");
        }
        return recoveryCode.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private String generateAccountCode() {
        return "STK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private String generateRecoveryCode() {
        StringBuilder builder = new StringBuilder("RC-");
        for (int index = 0; index < 12; index++) {
            if (index > 0 && index % 4 == 0) {
                builder.append('-');
            }
            builder.append(RECOVERY_CODE_ALPHABET[secureRandom.nextInt(RECOVERY_CODE_ALPHABET.length)]);
        }
        return builder.toString();
    }

    private String hashRecoveryCode(String recoveryCode) {
        return hashValue(normalizeRecoveryCode(recoveryCode));
    }

    private String hashValue(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private boolean matchesRecoveryCode(String recoveryCode, String expectedHash) {
        if (expectedHash == null || expectedHash.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                hashRecoveryCode(recoveryCode).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}
