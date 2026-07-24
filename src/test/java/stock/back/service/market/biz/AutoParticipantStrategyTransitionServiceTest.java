package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import stock.back.service.database.entity.StockAccount;
import stock.back.service.trading.biz.AccountOrderCleanupService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AutoParticipantStrategyTransitionServiceTest {

    private final AccountOrderCleanupService accountOrderCleanupService = mock(AccountOrderCleanupService.class);
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private AutoParticipantStrategyTransitionService service;
    private StockAccount account;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:auto_participant_strategy_transition;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists stock_auto_participant_funding_budget");
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_funding_budget(
                    id bigint primary key,
                    account_id bigint not null,
                    available_amount decimal(19,2) not null,
                    reserved_amount decimal(19,2) not null,
                    status varchar(20) not null,
                    updated_at timestamp not null
                )
                """
        );
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = new AutoParticipantStrategyTransitionService(accountOrderCleanupService, jdbcTemplate);
        account = StockAccount.open("stock-auto-transition");
        ReflectionTestUtils.setField(account, "id", 101L);
    }

    @Test
    void retireOpenOrdersAndFundingBudgets_afterOrderCleanup_expiresAvailableBudgets() {
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_funding_budget(
                    id, account_id, available_amount, reserved_amount, status, updated_at
                ) values (1, 101, 50000, 0, 'ACTIVE', current_timestamp)
                """
        );
        LocalDateTime retiredAt = LocalDateTime.of(2027, 1, 18, 12, 0);

        transactionTemplate.executeWithoutResult(ignored ->
                service.retireOpenOrdersAndFundingBudgets(account, retiredAt)
        );

        verify(accountOrderCleanupService).cancelOpenOrderBookOrders(account);
        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_auto_participant_funding_budget where id = 1",
                String.class
        )).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                "select available_amount from stock_auto_participant_funding_budget where id = 1",
                BigDecimal.class
        )).isEqualByComparingTo("50000.00");
    }

    @Test
    void retireOpenOrdersAndFundingBudgets_remainingReservation_rollsBackTransition() {
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_funding_budget(
                    id, account_id, available_amount, reserved_amount, status, updated_at
                ) values (1, 101, 0, 50000, 'ACTIVE', current_timestamp)
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored ->
                service.retireOpenOrdersAndFundingBudgets(
                        account,
                        LocalDateTime.of(2027, 1, 18, 12, 0)
                )
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Funding reservations remain");

        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_auto_participant_funding_budget where id = 1",
                String.class
        )).isEqualTo("ACTIVE");
    }

    @Test
    void retireAllOpenOrdersAndFundingBudgets_withdrawal_cancelsEveryMarketOrder() {
        LocalDateTime retiredAt = LocalDateTime.of(2027, 1, 18, 12, 0);

        transactionTemplate.executeWithoutResult(ignored ->
                service.retireAllOpenOrdersAndFundingBudgets(account, retiredAt)
        );

        verify(accountOrderCleanupService).cancelOpenOrdersForDetach(account);
    }
}
