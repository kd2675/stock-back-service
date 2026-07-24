package stock.back.service.market.biz;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.database.entity.StockAccount;
import stock.back.service.trading.biz.AccountOrderCleanupService;

@Service
class AutoParticipantStrategyTransitionService {

    private final AccountOrderCleanupService accountOrderCleanupService;
    private final JdbcTemplate jdbcTemplate;

    AutoParticipantStrategyTransitionService(
            AccountOrderCleanupService accountOrderCleanupService,
            JdbcTemplate jdbcTemplate
    ) {
        this.accountOrderCleanupService = accountOrderCleanupService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void retireOpenOrdersAndFundingBudgets(StockAccount account, LocalDateTime retiredAt) {
        retireOpenOrdersAndFundingBudgets(account, retiredAt, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void retireAllOpenOrdersAndFundingBudgets(StockAccount account, LocalDateTime retiredAt) {
        retireOpenOrdersAndFundingBudgets(account, retiredAt, true);
    }

    private void retireOpenOrdersAndFundingBudgets(
            StockAccount account,
            LocalDateTime retiredAt,
            boolean allMarkets
    ) {
        if (account == null || account.getId() == null) {
            return;
        }
        if (retiredAt == null) {
            throw new IllegalArgumentException("Auto-participant strategy transition time is required");
        }
        if (allMarkets) {
            accountOrderCleanupService.cancelOpenOrdersForDetach(account);
        } else {
            accountOrderCleanupService.cancelOpenOrderBookOrders(account);
        }
        int reservedBudgetCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_auto_participant_funding_budget
                 where account_id = ?
                   and status = 'ACTIVE'
                   and reserved_amount > 0
                """,
                Integer.class,
                account.getId()
        );
        if (reservedBudgetCount > 0) {
            throw new IllegalStateException(
                    "Funding reservations remain after auto-participant order cleanup: accountId=%d, count=%d"
                            .formatted(account.getId(), reservedBudgetCount)
            );
        }
        jdbcTemplate.update(
                """
                update stock_auto_participant_funding_budget
                   set status = 'EXPIRED',
                       updated_at = ?
                 where account_id = ?
                   and status = 'ACTIVE'
                   and reserved_amount = 0
                """,
                retiredAt,
                account.getId()
        );
    }
}
