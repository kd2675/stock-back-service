package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.market.vo.AutoParticipantLifecycleScope;
import stock.back.service.market.vo.AutoParticipantResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

class AutoMarketStatusDataLoaderTest {

    private JdbcTemplate jdbcTemplate;
    private AutoMarketStatusDataLoader dataLoader;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:auto_market_status_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        dataLoader = new AutoMarketStatusDataLoader(
                jdbcTemplate,
                mock(StockAutoParticipantSymbolConfigRepository.class),
                mock(ListingAutoAccountLedgerQueryService.class)
        );
    }

    @Test
    void loadAutoParticipantStatusResponses_expiredActiveBudget_isExcludedFromAvailableStatus() {
        LocalDate businessDate = LocalDate.of(2027, 1, 18);
        LocalDateTime now = businessDate.atStartOfDay();
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(state_id, active_business_date)
                values ('DEFAULT', ?)
                """,
                businessDate
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(
                    user_key, display_name, enabled, profile_type,
                    created_at, updated_at
                ) values ('auto-1', '자동 참여자 1', true, 'PAYDAY_ACCUMULATOR', ?, ?)
                """,
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_account(id, user_key, status, cash_balance)
                values (1, 'auto-1', 'ACTIVE', 100000)
                """
        );
        insertBudget(1L, "PAYDAY", new BigDecimal("1000"), new BigDecimal("0"),
                new BigDecimal("100"), businessDate.minusDays(1));
        insertBudget(2L, "PAYDAY", new BigDecimal("2000"), new BigDecimal("50"),
                new BigDecimal("200"), businessDate);
        insertBudget(3L, "DIVIDEND", new BigDecimal("3000"), new BigDecimal("0"),
                new BigDecimal("300"), null);

        AutoParticipantResponse response = dataLoader.loadAutoParticipantStatusResponses().getFirst();

        assertThat(response.paydayAvailableBudget()).isEqualByComparingTo("2000.00");
        assertThat(response.dividendAvailableBudget()).isEqualByComparingTo("3000.00");
        assertThat(response.activeFundingBudgetCount()).isEqualTo(2L);
        assertThat(response.fundingReservedAmount()).isEqualByComparingTo("50.00");
        assertThat(response.fundingSpentAmount()).isEqualByComparingTo("600.00");
    }

    @Test
    void loadAutoParticipantStatusResponses_withdrawnScope_returnsOnlyWithdrawnParticipants() {
        LocalDateTime now = LocalDate.of(2027, 1, 18).atStartOfDay();
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(
                    user_key, display_name, enabled, profile_type,
                    created_at, updated_at, withdrawn_at
                ) values ('auto-current', '현재 참여자', true, 'NOISE_TRADER', ?, ?, null)
                """,
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(
                    user_key, display_name, enabled, profile_type,
                    created_at, updated_at, withdrawn_at
                ) values ('auto-withdrawn', '휴면 참여자', false, 'CONTRARIAN', ?, ?, ?)
                """,
                now.minusDays(3),
                now.minusDays(1),
                now.minusDays(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_account(id, user_key, status, cash_balance)
                values (1, 'auto-current', 'ACTIVE', 100000),
                       (2, 'auto-withdrawn', 'CLOSED', 0)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_withdrawal(
                    id, participant_user_key, account_id, returned_cash_amount,
                    returned_share_quantity, returned_symbol_count
                ) values (1, 'auto-withdrawn', 2, 250000, 120, 2)
                """
        );

        var currentParticipants = dataLoader.loadAutoParticipantStatusResponses(
                AutoParticipantLifecycleScope.CURRENT
        );
        var withdrawnParticipants = dataLoader.loadAutoParticipantStatusResponses(
                AutoParticipantLifecycleScope.WITHDRAWN
        );

        assertThat(List.of(currentParticipants.getFirst(), withdrawnParticipants.getFirst()))
                .extracting(
                        AutoParticipantResponse::userKey,
                        AutoParticipantResponse::cashBalance,
                        AutoParticipantResponse::withdrawnAt,
                        AutoParticipantResponse::withdrawalReturnedCashAmount,
                        AutoParticipantResponse::withdrawalReturnedShareQuantity,
                        AutoParticipantResponse::accountClosedOnWithdrawal
                )
                .containsExactly(
                        tuple("auto-current", new BigDecimal("100000.00"), null, BigDecimal.ZERO, 0L, false),
                        tuple(
                                "auto-withdrawn",
                                new BigDecimal("0.00"),
                                now.minusDays(1),
                                new BigDecimal("250000.00"),
                                120L,
                                true
                        )
                );
    }

    private void insertBudget(
            long id,
            String budgetType,
            BigDecimal availableAmount,
            BigDecimal reservedAmount,
            BigDecimal spentAmount,
            LocalDate expiresBusinessDate
    ) {
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_funding_budget(
                    id, account_id, budget_type, available_amount, reserved_amount,
                    spent_amount, expires_business_date, status
                ) values (?, 1, ?, ?, ?, ?, ?, 'ACTIVE')
                """,
                id,
                budgetType,
                availableAmount,
                reservedAmount,
                spentAmount,
                expiresBusinessDate
        );
    }

    private void createSchema() {
        jdbcTemplate.execute(
                """
                create table stock_market_business_state(
                    state_id varchar(20) primary key,
                    active_business_date date not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant(
                    user_key varchar(64) primary key,
                    display_name varchar(80) not null,
                    enabled boolean not null,
                    profile_type varchar(40) not null,
                    behavior_seed bigint,
                    recurring_cash_amount decimal(19,2),
                    recurring_cash_interval_value decimal(12,4),
                    recurring_cash_interval_unit varchar(20),
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    withdrawn_at timestamp
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_profile_config(
                    profile_type varchar(40) primary key,
                    behavior_model_version varchar(20) not null
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
                    returned_symbol_count int not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_account(
                    id bigint primary key,
                    user_key varchar(64),
                    status varchar(20) not null,
                    cash_balance decimal(19,2) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_funding_budget(
                    id bigint primary key,
                    account_id bigint not null,
                    budget_type varchar(20) not null,
                    available_amount decimal(19,2) not null,
                    reserved_amount decimal(19,2) not null,
                    spent_amount decimal(19,2) not null,
                    expires_business_date date,
                    status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_position_state(
                    account_id bigint not null,
                    holding_trading_days int not null,
                    average_down_rounds int not null
                )
                """
        );
    }
}
