package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountParticipantCategory;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.market.biz.SimulationClockService;
import stock.back.service.market.biz.MarketLedgerFreezeGuard;
import stock.back.service.trading.vo.AccountCashAdjustmentRequest;
import stock.back.service.trading.vo.AccountCashAdjustmentResponse;
import stock.back.service.trading.vo.AccountReconnectRequest;
import stock.back.service.trading.vo.AccountResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountService {

    private static final int RECOVERY_DAYS = 30;
    private static final int PURGE_DAYS = 90;

    private final StockAccountRepository stockAccountRepository;
    private final StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AccountOrderCleanupService accountOrderCleanupService;
    private final SimulationClockService simulationClockService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;
    private final AccountRecoveryCredentialGenerator credentialGenerator = new AccountRecoveryCredentialGenerator();

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
        marketLedgerFreezeGuard.acquireMutationPermit("account detach");
        StockAccount account = requireAccountForUpdate(userKey);
        accountOrderCleanupService.cancelOpenOrdersForDetach(account);
        account.assignAccountCodeIfMissing(credentialGenerator.generateAccountCode());
        String recoveryCode = credentialGenerator.generateRecoveryCode();
        LocalDateTime now = LocalDateTime.now();
        account.detach(
                credentialGenerator.hashValue(userKey),
                credentialGenerator.hashRecoveryCode(recoveryCode),
                recoveryCode,
                now.plusDays(RECOVERY_DAYS),
                now.plusDays(PURGE_DAYS)
        );
        return toResponse(account);
    }

    @Transactional(noRollbackFor = StockException.class)
    public AccountResponse reconnectAccount(String userKey, AccountReconnectRequest request) {
        validateUserKey(userKey);
        marketLedgerFreezeGuard.acquireMutationPermit("account reconnect");
        if (findAccount(userKey).isPresent()) {
            throw StockException.conflict("Active account already exists");
        }
        String accountCode = credentialGenerator.normalizeAccountCode(request == null ? null : request.accountCode());
        String recoveryCode = credentialGenerator.normalizeRecoveryCode(request == null ? null : request.recoveryCode());
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
        if (!credentialGenerator.matchesRecoveryCode(recoveryCode, account.getRecoveryCodeHash())) {
            throw StockException.unauthorized("Invalid recovery code");
        }
        String nextRecoveryCode = credentialGenerator.generateRecoveryCode();
        account.reconnect(userKey, credentialGenerator.hashRecoveryCode(nextRecoveryCode), nextRecoveryCode);
        return toResponse(account);
    }

    @Transactional
    public AccountCashAdjustmentResponse adjustUserAccountCash(
            String userKey,
            AccountCashAdjustmentRequest request,
            String adminUserKey
    ) {
        validateUserKey(userKey);
        BigDecimal amount = request == null ? null : request.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Adjustment amount must be positive");
        }
        String adjustmentType = normalizeAdjustmentType(request == null ? null : request.adjustmentType());
        if (!"DEPOSIT".equals(adjustmentType) && !"WITHDRAW".equals(adjustmentType)) {
            throw StockException.badRequest("Adjustment type must be DEPOSIT or WITHDRAW");
        }

        marketLedgerFreezeGuard.acquireMutationPermit("user cash adjustment");
        StockAccount account = stockAccountRepository.findByUserKeyAndStatusForUpdate(userKey, StockAccountStatus.ACTIVE)
                .orElseThrow(() -> StockException.notFound("User account is not opened yet: " + userKey));
        if (account.getParticipantCategory()
                != StockAccountParticipantCategory.MANUAL_PARTICIPANT) {
            throw StockException.conflict(
                    "System participant cash must be adjusted from its dedicated admin workflow"
            );
        }
        LocalDateTime createdAt = simulationClockService.currentMarketDateTime();
        if ("DEPOSIT".equals(adjustmentType)) {
            account.depositCash(amount, createdAt);
            stockAccountCashFlowRepository.save(StockAccountCashFlow.adminDeposit(account.getId(), amount, normalizeText(adminUserKey), createdAt));
        } else if (!account.withdrawCash(amount, createdAt)) {
            throw StockException.badRequest("Insufficient user cash balance");
        } else {
            stockAccountCashFlowRepository.save(StockAccountCashFlow.adminWithdraw(account.getId(), amount, normalizeText(adminUserKey), createdAt));
        }

        return new AccountCashAdjustmentResponse(
                account.getId(),
                account.getUserKey(),
                adjustmentType,
                amount,
                account.getCashBalance(),
                account.getUpdatedAt()
        );
    }

    private void validateUserKey(String userKey) {
        if (userKey == null || userKey.isBlank()) {
            throw StockException.unauthorized("Login required");
        }
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeAdjustmentType(String value) {
        return normalizeText(value).toUpperCase(Locale.ROOT);
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
        marketLedgerFreezeGuard.acquireMutationPermit("account opening");
        try {
            String recoveryCode = credentialGenerator.generateRecoveryCode();
            LocalDateTime createdAt = simulationClockService.currentMarketDateTime();
            StockAccount account = stockAccountRepository.saveAndFlush(StockAccount.open(
                    userKey,
                    credentialGenerator.generateAccountCode(),
                    credentialGenerator.hashRecoveryCode(recoveryCode),
                    recoveryCode,
                    createdAt
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
        account.assignAccountCodeIfMissing(credentialGenerator.generateAccountCode());
        String recoveryCode = credentialGenerator.generateRecoveryCode();
        account.issueRecoveryCode(credentialGenerator.hashRecoveryCode(recoveryCode), recoveryCode);
        return account;
    }

    private StockAccount openAccountForUpdateAfterCreateRace(String userKey) {
        marketLedgerFreezeGuard.acquireMutationPermit("account opening");
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
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        String recoveryCode = credentialGenerator.generateRecoveryCode();
        jdbcTemplate.update(
                """
                insert into stock_account(
                    user_key, account_code, recovery_code_hash, status,
                    cash_balance, created_at, updated_at
                )
                values (?, ?, ?, 'ACTIVE', 0.00, ?, ?)
                """,
                userKey,
                credentialGenerator.generateAccountCode(),
                credentialGenerator.hashRecoveryCode(recoveryCode),
                now,
                now
        );
    }

    private void applyOpeningGrant(StockAccount account) {
        if (openingGrantAmount == null || openingGrantAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        LocalDateTime createdAt = simulationClockService.currentMarketDateTime();
        account.depositCash(openingGrantAmount, createdAt);
        stockAccountCashFlowRepository.save(StockAccountCashFlow.openingGrant(
                account.getId(),
                openingGrantAmount,
                createdAt
        ));
    }

}
