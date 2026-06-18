package stock.back.service.trading.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.repository.StockAccountRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private StockAccountRepository stockAccountRepository;
    private JdbcTemplate jdbcTemplate;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        stockAccountRepository = mock(StockAccountRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        accountService = new AccountService(stockAccountRepository, jdbcTemplate);
        ReflectionTestUtils.setField(accountService, "initialCash", new BigDecimal("10000000"));
    }

    @Test
    void getOrOpenAccount_createRace_returnsExistingAccountAfterDuplicateKey() {
        StockAccount existingAccount = StockAccount.open("race-user", new BigDecimal("10000000"));
        when(stockAccountRepository.findByUserKey("race-user"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingAccount));
        when(jdbcTemplate.update(any(String.class), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("duplicate account"));

        StockAccount account = accountService.getOrOpenAccount("race-user");

        assertThat(account).isSameAs(existingAccount);
    }

    @Test
    void getOrOpenAccountForUpdate_createRace_returnsLockedExistingAccountAfterDuplicateKey() {
        StockAccount existingAccount = StockAccount.open("race-lock-user", new BigDecimal("10000000"));
        when(stockAccountRepository.findByUserKeyForUpdate("race-lock-user"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingAccount));
        when(jdbcTemplate.update(any(String.class), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("duplicate account"));

        StockAccount account = accountService.getOrOpenAccountForUpdate("race-lock-user");

        assertThat(account).isSameAs(existingAccount);
    }
}
