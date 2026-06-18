package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.trading.vo.AccountResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final StockAccountRepository stockAccountRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${stock.trading.initial-cash:10000000}")
    private BigDecimal initialCash;

    @Transactional
    public StockAccount getOrOpenAccount(String userKey) {
        validateUserKey(userKey);
        return stockAccountRepository.findByUserKey(userKey)
                .orElseGet(() -> openAccountAfterCreateRace(userKey));
    }

    @Transactional
    public StockAccount getOrOpenAccountForUpdate(String userKey) {
        validateUserKey(userKey);
        return stockAccountRepository.findByUserKeyForUpdate(userKey)
                .orElseGet(() -> openAccountForUpdateAfterCreateRace(userKey));
    }

    private void validateUserKey(String userKey) {
        if (userKey == null || userKey.isBlank()) {
            throw StockException.unauthorized("Login required");
        }
    }

    public AccountResponse toResponse(StockAccount account) {
        return new AccountResponse(account.getUserKey(), account.getCashBalance(), account.getInitialCash());
    }

    private StockAccount openAccountAfterCreateRace(String userKey) {
        try {
            insertAccount(userKey);
        } catch (DataIntegrityViolationException ex) {
            return stockAccountRepository.findByUserKey(userKey)
                    .orElseThrow(() -> ex);
        }
        return stockAccountRepository.findByUserKey(userKey)
                .orElseThrow(() -> StockException.notFound("Account not found after opening"));
    }

    private StockAccount openAccountForUpdateAfterCreateRace(String userKey) {
        try {
            insertAccount(userKey);
        } catch (DataIntegrityViolationException ex) {
            return stockAccountRepository.findByUserKeyForUpdate(userKey)
                    .orElseThrow(() -> ex);
        }
        return stockAccountRepository.findByUserKeyForUpdate(userKey)
                .orElseThrow(() -> StockException.notFound("Account not found after opening"));
    }

    private void insertAccount(String userKey) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, initial_cash, created_at, updated_at)
                values (?, ?, ?, ?, ?)
                """,
                userKey,
                initialCash,
                initialCash,
                now,
                now
        );
    }
}
