package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountCashFlowReason;
import stock.back.service.database.entity.StockAccountParticipantCategory;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoParticipantWithdrawalSettlementServiceTest {

    private static final LocalDateTime SETTLED_AT = LocalDateTime.of(2027, 1, 22, 18, 5);

    private StockAccountRepository stockAccountRepository;
    private StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private AutoParticipantStrategyTransitionService strategyTransitionService;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private AutoParticipantWithdrawalSettlementService service;
    private StockAccount participantAccount;
    private StockAccount custodyAccount;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:auto_participant_withdrawal;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop all objects");
        createSchema();

        stockAccountRepository = mock(StockAccountRepository.class);
        stockAccountCashFlowRepository = mock(StockAccountCashFlowRepository.class);
        strategyTransitionService = mock(AutoParticipantStrategyTransitionService.class);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = new AutoParticipantWithdrawalSettlementService(
                stockAccountRepository,
                stockAccountCashFlowRepository,
                strategyTransitionService,
                new NamedParameterJdbcTemplate(dataSource)
        );

        participantAccount = account(
                101L,
                "stock-auto-withdraw",
                StockAccountParticipantCategory.AUTO_PARTICIPANT,
                new BigDecimal("5000000.00")
        );
        custodyAccount = account(
                201L,
                "stock-system-custody",
                StockAccountParticipantCategory.SYSTEM_CUSTODY,
                BigDecimal.ZERO
        );
        custodyAccount.assignSelfTradeGroupId("SYSTEM_CUSTODY:DEFAULT", SETTLED_AT);
        when(stockAccountRepository.findByUserKey("stock-auto-withdraw"))
                .thenReturn(Optional.of(participantAccount));
        when(stockAccountRepository.findAllByIdInForUpdate(any()))
                .thenAnswer(invocation -> lockedAccounts(invocation.getArgument(0)));

        insertAccount(participantAccount);
        insertAccount(custodyAccount);
        insertSystemCustodyMapping();
    }

    @Test
    void settle_assetsExist_returnsSharesWithdrawsCashAndClosesAccount() {
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (101, 'DEMO001', 10, 0, 100.00, current_timestamp),
                       (201, 'DEMO001', 5, 0, 200.00, current_timestamp)
                """
        );

        AutoParticipantWithdrawalSettlementService.WithdrawalSettlement result =
                transactionTemplate.execute(status ->
                        service.settle("stock-auto-withdraw", "stock-admin", SETTLED_AT)
                );

        assertThat(result).isNotNull();
        assertThat(result.returnedCashAmount()).isEqualByComparingTo("5000000.00");
        assertThat(result.returnedShareQuantity()).isEqualTo(10L);
        assertThat(result.returnedSymbolCount()).isEqualTo(1);
        assertThat(result.accountClosed()).isTrue();
        assertThat(participantAccount.getStatus()).isEqualTo(StockAccountStatus.CLOSED);
        assertThat(participantAccount.getCashBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryLong("select quantity from stock_holding where account_id = 101 and symbol = 'DEMO001'"))
                .isZero();
        assertThat(queryLong("select quantity from stock_holding where account_id = 201 and symbol = 'DEMO001'"))
                .isEqualTo(15L);
        assertThat(jdbcTemplate.queryForObject(
                "select average_price from stock_holding where account_id = 201 and symbol = 'DEMO001'",
                BigDecimal.class
        )).isEqualByComparingTo("133.33");
        assertThat(queryLong("select count(*) from stock_auto_participant_withdrawal")).isEqualTo(1L);
        assertThat(queryLong("select count(*) from stock_auto_participant_share_return")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select receiver_account_id, receiver_role, transfer_reason
                  from stock_auto_participant_share_return
                 where withdrawal_id = 1
                   and symbol = 'DEMO001'
                """
        )).containsEntry("RECEIVER_ACCOUNT_ID", 201L)
                .containsEntry("RECEIVER_ROLE", "SYSTEM_CUSTODY")
                .containsEntry("TRANSFER_REASON", "AUTO_PARTICIPANT_WITHDRAWAL_CUSTODY");

        verify(strategyTransitionService).retireAllOpenOrdersAndFundingBudgets(participantAccount, SETTLED_AT);
        ArgumentCaptor<StockAccountCashFlow> cashFlowCaptor = ArgumentCaptor.forClass(StockAccountCashFlow.class);
        verify(stockAccountCashFlowRepository).save(cashFlowCaptor.capture());
        assertThat(cashFlowCaptor.getValue().getReason()).isEqualTo(StockAccountCashFlowReason.ADMIN_WITHDRAW);
        assertThat(cashFlowCaptor.getValue().getAmount()).isEqualByComparingTo("5000000.00");
        assertThat(cashFlowCaptor.getValue().getCreatedBy()).isEqualTo("stock-admin");
    }

    @Test
    void settle_withoutListingUnderwriter_transfersSharesToSystemCustody() {
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (101, 'DEMO404', 10, 0, 100.00, current_timestamp)
                """
        );

        transactionTemplate.executeWithoutResult(status ->
                service.settle("stock-auto-withdraw", "stock-admin", SETTLED_AT)
        );

        assertThat(participantAccount.getStatus()).isEqualTo(StockAccountStatus.CLOSED);
        assertThat(participantAccount.getCashBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryLong("select quantity from stock_holding where account_id = 101 and symbol = 'DEMO404'"))
                .isZero();
        assertThat(queryLong("select quantity from stock_holding where account_id = 201 and symbol = 'DEMO404'"))
                .isEqualTo(10L);
        assertThat(queryLong("select count(*) from stock_auto_participant_withdrawal")).isEqualTo(1L);
        verify(strategyTransitionService).retireAllOpenOrdersAndFundingBudgets(participantAccount, SETTLED_AT);
        verify(stockAccountCashFlowRepository).save(any());
    }

    @Test
    void settle_missingSystemCustodyMapping_rollsBackWithoutChangingAssets() {
        jdbcTemplate.update("delete from stock_market_participant_account");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (101, 'DEMO404', 10, 0, 100.00, current_timestamp)
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.settle("stock-auto-withdraw", "stock-admin", SETTLED_AT)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("SYSTEM_CUSTODY");

        assertThat(participantAccount.getStatus()).isEqualTo(StockAccountStatus.ACTIVE);
        assertThat(participantAccount.getCashBalance()).isEqualByComparingTo("5000000.00");
        assertThat(queryLong("select quantity from stock_holding where account_id = 101 and symbol = 'DEMO404'"))
                .isEqualTo(10L);
        assertThat(queryLong("select count(*) from stock_auto_participant_withdrawal")).isZero();
        verify(strategyTransitionService, never()).retireAllOpenOrdersAndFundingBudgets(any(), any());
        verify(stockAccountCashFlowRepository, never()).save(any());
    }

    @Test
    void settle_futureSystemCustodyMapping_rejectsBeforeOrderCleanup() {
        jdbcTemplate.update(
                """
                update stock_market_participant_account
                   set effective_from = date '2027-01-23'
                 where account_id = 201
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (101, 'DEMO404', 10, 0, 100.00, current_timestamp)
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.settle("stock-auto-withdraw", "stock-admin", SETTLED_AT)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("SYSTEM_CUSTODY");

        verify(strategyTransitionService, never()).retireAllOpenOrdersAndFundingBudgets(any(), any());
        assertThat(participantAccount.getStatus()).isEqualTo(StockAccountStatus.ACTIVE);
    }

    @Test
    void settle_systemCustodyHasReservedHolding_rejectsBeforeOrderCleanup() {
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (101, 'DEMO001', 10, 0, 100.00, current_timestamp),
                       (201, 'DEMO001', 5, 1, 200.00, current_timestamp)
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.settle("stock-auto-withdraw", "stock-admin", SETTLED_AT)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("must not contain open orders or reserved holdings");

        verify(strategyTransitionService, never()).retireAllOpenOrdersAndFundingBudgets(any(), any());
        assertThat(participantAccount.getStatus()).isEqualTo(StockAccountStatus.ACTIVE);
    }

    @Test
    void settle_systemCustodyHasOpenOrder_rejectsBeforeOrderCleanup() {
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (101, 'DEMO001', 10, 0, 100.00, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_order(id, account_id, status, quantity, filled_quantity)
                values (1, 201, 'PENDING', 1, 0)
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.settle("stock-auto-withdraw", "stock-admin", SETTLED_AT)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("must not contain open orders or reserved holdings");

        verify(strategyTransitionService, never()).retireAllOpenOrdersAndFundingBudgets(any(), any());
        assertThat(participantAccount.getStatus()).isEqualTo(StockAccountStatus.ACTIVE);
    }

    @Test
    void settle_pendingCorporateActionRight_rejectsBeforeOrderCleanup() {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(id, account_id, status)
                values (1, 101, 'SUBSCRIBED')
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.settle("stock-auto-withdraw", "stock-admin", SETTLED_AT)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("pending corporate-action rights");

        verify(strategyTransitionService, never()).retireAllOpenOrdersAndFundingBudgets(any(), any());
        assertThat(participantAccount.getStatus()).isEqualTo(StockAccountStatus.ACTIVE);
    }

    @Test
    void settle_frozenCorporateActionRightNotMaterializedYet_rejectsBeforeOrderCleanup() {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                    id, symbol, action_type, status, entitlement_close_run_id
                )
                values (1, 'DEMO001', 'CASH_DIVIDEND', 'ANNOUNCED', 500)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding_snapshot(close_run_id, account_id, symbol, quantity)
                values (500, 101, 'DEMO001', 10)
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.settle("stock-auto-withdraw", "stock-admin", SETTLED_AT)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("pending corporate-action rights");

        verify(strategyTransitionService, never()).retireAllOpenOrdersAndFundingBudgets(any(), any());
        assertThat(participantAccount.getStatus()).isEqualTo(StockAccountStatus.ACTIVE);
    }

    private StockAccount account(
            long id,
            String userKey,
            StockAccountParticipantCategory participantCategory,
            BigDecimal cashBalance
    ) {
        StockAccount account = StockAccount.open(userKey);
        ReflectionTestUtils.setField(account, "id", id);
        account.assignParticipantCategory(participantCategory, SETTLED_AT);
        if (cashBalance.compareTo(BigDecimal.ZERO) > 0) {
            account.depositCash(cashBalance, SETTLED_AT);
        }
        return account;
    }

    private List<StockAccount> lockedAccounts(Collection<Long> accountIds) {
        return List.of(participantAccount, custodyAccount).stream()
                .filter(account -> accountIds.contains(account.getId()))
                .sorted((left, right) -> left.getId().compareTo(right.getId()))
                .toList();
    }

    private void insertAccount(StockAccount account) {
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, status, participant_category, self_trade_group_id
                )
                values (?, ?, ?, ?, ?)
                """,
                account.getId(),
                account.getUserKey(),
                account.getStatus().name(),
                account.getParticipantCategory().name(),
                account.getSelfTradeGroupId()
        );
    }

    private void insertSystemCustodyMapping() {
        jdbcTemplate.update(
                """
                insert into stock_market_participant(
                    id, participant_code, participant_type, status
                )
                values (301, 'SYSTEM_CUSTODY', 'SYSTEM_CUSTODY', 'ACTIVE')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_market_participant_account(
                    participant_id, account_id, account_role, status,
                    effective_from, effective_to
                )
                values (301, 201, 'SYSTEM_CUSTODY', 'ACTIVE', date '1970-01-01', null)
                """
        );
    }

    private long queryLong(String sql) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class);
        return result == null ? 0L : result;
    }

    private void createSchema() {
        jdbcTemplate.execute(
                """
                create table stock_account(
                    id bigint primary key,
                    user_key varchar(64) not null,
                    status varchar(20) not null,
                    participant_category varchar(30) not null,
                    self_trade_group_id varchar(80)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_market_participant(
                    id bigint primary key,
                    participant_code varchar(64) not null,
                    participant_type varchar(40) not null,
                    status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_market_participant_account(
                    participant_id bigint not null,
                    account_id bigint not null,
                    account_role varchar(40) not null,
                    status varchar(20) not null,
                    effective_from date not null,
                    effective_to date
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_holding(
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null,
                    average_price decimal(19,2) not null,
                    updated_at timestamp not null,
                    primary key(account_id, symbol)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_order(
                    id bigint primary key,
                    account_id bigint not null,
                    status varchar(30) not null,
                    quantity bigint not null,
                    filled_quantity bigint not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_corporate_action_entitlement(
                    id bigint primary key,
                    account_id bigint not null,
                    status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_corporate_action(
                    id bigint primary key,
                    symbol varchar(20) not null,
                    action_type varchar(40) not null,
                    status varchar(30) not null,
                    entitlement_close_run_id bigint null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_holding_snapshot(
                    close_run_id bigint not null,
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_withdrawal(
                    id bigint generated by default as identity primary key,
                    participant_user_key varchar(64) not null unique,
                    account_id bigint not null,
                    returned_cash_amount decimal(19,2) not null,
                    returned_share_quantity bigint not null,
                    returned_symbol_count int not null,
                    created_by varchar(64) not null,
                    created_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_share_return(
                    withdrawal_id bigint not null,
                    symbol varchar(20) not null,
                    underwriter_account_id bigint not null,
                    receiver_account_id bigint not null,
                    receiver_role varchar(40) not null,
                    transfer_reason varchar(50) not null,
                    quantity bigint not null,
                    source_average_price decimal(19,2) not null,
                    created_at timestamp not null,
                    primary key(withdrawal_id, symbol)
                )
                """
        );
    }
}
