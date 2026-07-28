package stock.back.service.trading.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountParticipantCategory;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.market.biz.SimulationClockService;
import stock.back.service.market.biz.MarketLedgerFreezeGuard;
import stock.back.service.trading.vo.AccountCashAdjustmentRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private static final LocalDateTime SIMULATION_NOW = LocalDateTime.of(2026, 7, 1, 10, 0);

    private StockAccountRepository stockAccountRepository;
    private StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private JdbcTemplate jdbcTemplate;
    private AccountOrderCleanupService accountOrderCleanupService;
    private SimulationClockService simulationClockService;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        stockAccountRepository = mock(StockAccountRepository.class);
        stockAccountCashFlowRepository = mock(StockAccountCashFlowRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        accountOrderCleanupService = mock(AccountOrderCleanupService.class);
        simulationClockService = mock(SimulationClockService.class);
        lenient().when(simulationClockService.currentMarketDateTime()).thenReturn(SIMULATION_NOW);
        accountService = new AccountService(
                stockAccountRepository,
                stockAccountCashFlowRepository,
                jdbcTemplate,
                accountOrderCleanupService,
                simulationClockService,
                mock(MarketLedgerFreezeGuard.class)
        );
        ReflectionTestUtils.setField(accountService, "openingGrantAmount", new BigDecimal("10000000"));
    }

    @Test
    void getOrOpenAccount_existingAccount_returnsExistingAccountWithoutCreate() {
        StockAccount existingAccount = StockAccount.open("existing-user");
        when(stockAccountRepository.findByUserKeyAndStatus("existing-user", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(existingAccount));

        StockAccount account = accountService.getOrOpenAccount("existing-user");

        assertThat(account).isSameAs(existingAccount);
        verify(stockAccountRepository, never()).saveAndFlush(any(StockAccount.class));
    }

    @Test
    void getOrOpenAccount_noAccount_returnsCreatedAccountWithoutSecondLookup() {
        when(stockAccountRepository.findByUserKeyAndStatus("new-user", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(stockAccountRepository.saveAndFlush(any(StockAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StockAccount account = accountService.getOrOpenAccount("new-user");

        assertThat(account.getUserKey()).isEqualTo("new-user");
        assertThat(account.getAccountCode()).startsWith("STK-");
        assertThat(account.getIssuedRecoveryCode()).startsWith("RC-");
        assertThat(account.getCreatedAt()).isEqualTo(SIMULATION_NOW);
        verify(stockAccountRepository, times(1)).findByUserKeyAndStatus("new-user", StockAccountStatus.ACTIVE);
        verify(stockAccountRepository).saveAndFlush(any(StockAccount.class));
    }

    @Test
    void getOrOpenAccountForUpdate_noAccount_insertsAccountWithSimulationTime() {
        StockAccount insertedAccount = StockAccount.open("lock-new-user", "STK-EXISTING", "hash", "RC-EXISTING", SIMULATION_NOW);
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("lock-new-user", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(insertedAccount));

        StockAccount account = accountService.getOrOpenAccountForUpdate("lock-new-user");

        assertThat(account).isSameAs(insertedAccount);
        verify(jdbcTemplate).update(
                contains("insert into stock_account"),
                eq("lock-new-user"),
                any(),
                any(),
                eq(SIMULATION_NOW),
                eq(SIMULATION_NOW)
        );
    }

    @Test
    void getOrOpenAccount_createRace_returnsExistingAccountAfterDuplicateKey() {
        StockAccount existingAccount = StockAccount.open("race-user");
        when(stockAccountRepository.findByUserKeyAndStatus("race-user", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingAccount));
        when(stockAccountRepository.saveAndFlush(any(StockAccount.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate account"));

        StockAccount account = accountService.getOrOpenAccount("race-user");

        assertThat(account).isSameAs(existingAccount);
    }

    @Test
    void openAccount_existingAccount_issuesRecoveryCredentials() {
        StockAccount existingAccount = StockAccount.open("existing-open-user");
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("existing-open-user", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(existingAccount));

        var response = accountService.openAccount("existing-open-user");

        assertThat(response.accountCode()).startsWith("STK-");
        assertThat(response.recoveryCode()).startsWith("RC-");
        assertThat(response.recoveryExpiresAt()).isNull();
        assertThat(response.purgeAfter()).isNull();
    }

    @Test
    void getOrOpenAccountForUpdate_createRace_returnsLockedExistingAccountAfterDuplicateKey() {
        StockAccount existingAccount = StockAccount.open("race-lock-user");
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("race-lock-user", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingAccount));
        when(jdbcTemplate.update(any(String.class), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("duplicate account"));

        StockAccount account = accountService.getOrOpenAccountForUpdate("race-lock-user");

        assertThat(account).isSameAs(existingAccount);
    }

    @Test
    void findAccount_noAccount_doesNotInsertAccount() {
        when(stockAccountRepository.findByUserKeyAndStatus("new-user", StockAccountStatus.ACTIVE)).thenReturn(Optional.empty());

        Optional<StockAccount> account = accountService.findAccount("new-user");

        assertThat(account).isEmpty();
        verify(stockAccountRepository).findByUserKeyAndStatus("new-user", StockAccountStatus.ACTIVE);
    }

    @Test
    void adjustUserAccountCash_deposit_addsCashAndWritesLedger() {
        StockAccount account = StockAccount.open("user-1");
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("user-1", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));

        var response = accountService.adjustUserAccountCash(
                "user-1",
                new AccountCashAdjustmentRequest("deposit", new BigDecimal("250000.00")),
                "admin-1"
        );

        assertThat(response.userKey()).isEqualTo("user-1");
        assertThat(response.adjustmentType()).isEqualTo("DEPOSIT");
        assertThat(response.cashBalance()).isEqualByComparingTo("250000.00");
        verify(stockAccountCashFlowRepository).save(any());
    }

    @Test
    void adjustUserAccountCash_withdrawOverBalance_throwsBadRequest() {
        StockAccount account = StockAccount.open("user-2");
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("user-2", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.adjustUserAccountCash(
                "user-2",
                new AccountCashAdjustmentRequest("WITHDRAW", new BigDecimal("1.00")),
                "admin-1"
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Insufficient user cash balance");

        verify(stockAccountCashFlowRepository, never()).save(any());
    }

    @Test
    void adjustUserAccountCash_institutionAccount_rejectsGenericCashWorkflow() {
        StockAccount account = StockAccount.open("stock-institution-institution-1");
        account.assignParticipantCategory(
                StockAccountParticipantCategory.INSTITUTIONAL_INVESTOR,
                SIMULATION_NOW
        );
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate(
                "stock-institution-institution-1",
                StockAccountStatus.ACTIVE
        )).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.adjustUserAccountCash(
                "stock-institution-institution-1",
                new AccountCashAdjustmentRequest("DEPOSIT", new BigDecimal("1.00")),
                "admin-1"
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("dedicated admin workflow");

        verify(stockAccountCashFlowRepository, never()).save(any());
    }

    @Test
    void adjustUserAccountCash_missingAdjustmentType_throwsBadRequest() {
        assertThatThrownBy(() -> accountService.adjustUserAccountCash(
                "user-3",
                new AccountCashAdjustmentRequest(null, new BigDecimal("1.00")),
                "admin-1"
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Adjustment type must be DEPOSIT or WITHDRAW");

        verify(stockAccountRepository, never()).findByUserKeyAndStatusForUpdate(any(), any());
        verify(stockAccountCashFlowRepository, never()).save(any());
    }
}
