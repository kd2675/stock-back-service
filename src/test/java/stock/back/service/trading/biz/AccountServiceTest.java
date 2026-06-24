package stock.back.service.trading.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.database.repository.StockAccountRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private StockAccountRepository stockAccountRepository;
    private StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private StockHoldingRepository stockHoldingRepository;
    private JdbcTemplate jdbcTemplate;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        stockAccountRepository = mock(StockAccountRepository.class);
        stockAccountCashFlowRepository = mock(StockAccountCashFlowRepository.class);
        stockHoldingRepository = mock(StockHoldingRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        accountService = new AccountService(stockAccountRepository, stockAccountCashFlowRepository, stockHoldingRepository, jdbcTemplate);
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
        verify(stockAccountRepository, times(1)).findByUserKeyAndStatus("new-user", StockAccountStatus.ACTIVE);
        verify(stockAccountRepository).saveAndFlush(any(StockAccount.class));
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
}
