package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import stock.back.service.market.vo.AutoParticipantWithdrawalAuditResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AutoParticipantWithdrawalQueryServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AutoParticipantWithdrawalQueryService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:auto_participant_withdrawal_query;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop all objects");
        createSchema();
        service = new AutoParticipantWithdrawalQueryService(JdbcClient.create(dataSource));
    }

    @Test
    void getWithdrawalAudits_filterIncludesSourceInvariantAndCustodyTransferDetails() {
        LocalDateTime createdAt = LocalDateTime.of(2027, 1, 22, 18, 5);
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, status, participant_category,
                    self_trade_group_id, cash_balance
                )
                values
                    (101, 'stock-auto-withdrawn', 'CLOSED', 'AUTO_PARTICIPANT', null, 12.00),
                    (201, 'stock-system-custody', 'ACTIVE', 'SYSTEM_CUSTODY',
                     'SYSTEM_CUSTODY:DEFAULT', 0.00)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity, average_price
                )
                values
                    (101, 'DEMO001', 2, 1, 100.00),
                    (201, 'DEMO001', 15, 0, 133.33)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_order(id, account_id, status)
                values (1, 101, 'PENDING')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(id, account_id, status)
                values (1, 101, 'SUBSCRIBED')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_withdrawal(
                    id, participant_user_key, account_id, returned_cash_amount,
                    returned_share_quantity, returned_symbol_count, created_by, created_at
                )
                values (1, 'stock-auto-withdrawn', 101, 5000000.00, 10, 1, 'stock-admin', ?)
                """,
                createdAt
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_share_return(
                    withdrawal_id, symbol, receiver_account_id,
                    receiver_role, transfer_reason,
                    quantity, source_average_price, created_at
                )
                values (
                    1, 'DEMO001', 201, 'SYSTEM_CUSTODY',
                    'AUTO_PARTICIPANT_WITHDRAWAL_CUSTODY', 10, 100.00, ?
                )
                """,
                createdAt
        );
        jdbcTemplate.update(
                """
                insert into stock_price(symbol, current_price)
                values ('DEMO001', 150.00)
                """
        );

        List<AutoParticipantWithdrawalAuditResponse> result =
                service.getWithdrawalAudits(List.of("stock-auto-withdrawn"));

        assertThat(result).singleElement().satisfies(audit -> {
            assertThat(audit.participantUserKey()).isEqualTo("stock-auto-withdrawn");
            assertThat(audit.sourceAccountStatus()).isEqualTo("CLOSED");
            assertThat(audit.sourceRemainingCashAmount()).isEqualByComparingTo("12.00");
            assertThat(audit.sourceRemainingShareQuantity()).isEqualTo(2L);
            assertThat(audit.sourceRemainingReservedShareQuantity()).isEqualTo(1L);
            assertThat(audit.sourceOpenOrderCount()).isEqualTo(1L);
            assertThat(audit.pendingCorporateActionRightCount()).isEqualTo(1L);
            assertThat(audit.shareTransfers()).singleElement().satisfies(transfer -> {
                assertThat(transfer.receiverRole()).isEqualTo("SYSTEM_CUSTODY");
                assertThat(transfer.transferReason())
                        .isEqualTo("AUTO_PARTICIPANT_WITHDRAWAL_CUSTODY");
                assertThat(transfer.receiverSelfTradeGroupId())
                        .isEqualTo("SYSTEM_CUSTODY:DEFAULT");
                assertThat(transfer.quantity()).isEqualTo(10L);
                assertThat(transfer.transferMarketValue()).isEqualByComparingTo("1500.00");
            });
        });
    }

    private void createSchema() {
        jdbcTemplate.execute(
                """
                create table stock_account(
                    id bigint primary key,
                    user_key varchar(64) not null,
                    status varchar(20) not null,
                    participant_category varchar(30) not null,
                    self_trade_group_id varchar(80),
                    cash_balance decimal(19,2) not null
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
                    average_price decimal(19,2) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_order(
                    id bigint primary key,
                    account_id bigint not null,
                    status varchar(30) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_corporate_action_entitlement(
                    id bigint primary key,
                    account_id bigint not null,
                    status varchar(30) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_withdrawal(
                    id bigint primary key,
                    participant_user_key varchar(64) not null,
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
                    receiver_account_id bigint not null,
                    receiver_role varchar(40) not null,
                    transfer_reason varchar(50) not null,
                    quantity bigint not null,
                    source_average_price decimal(19,2) not null,
                    created_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_price(
                    symbol varchar(20) primary key,
                    current_price decimal(19,2) not null
                )
                """
        );
    }
}
