package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.database.entity.AutoParticipantProfileType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoMarketSummaryStatusQueryTest {

    @Test
    void getSummaryStatus_withRuntimeAndSalaryEligibility_readsAggregateWithJdbcClient() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("auto_market_summary_status_full_test", true);
        AutoMarketSummaryStatusQuery query = new AutoMarketSummaryStatusQuery(jdbcTemplate, simulationClockService());
        seedSummaryRows(jdbcTemplate);

        var response = query.getSummaryStatus(true, true);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(3L);
        assertThat(response.participantCount()).isEqualTo(3L);
        assertThat(response.participantProfileConfigCount()).isEqualTo(AutoParticipantProfileType.values().length);
        assertThat(response.listingAutoAccountCount()).isEqualTo(2L);
        assertThat(response.enabledParticipantCount()).isEqualTo(2L);
        assertThat(response.salaryEligibleParticipantCount()).isEqualTo(2L);
        assertThat(response.openAutoOrderCount()).isEqualTo(2L);
        assertThat(response.todayAutoExecutionCount()).isEqualTo(2L);
    }

    @Test
    void getSummaryStatus_withoutSalaryEligibility_doesNotRequireSalaryTables() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("auto_market_summary_status_no_salary_test", false);
        AutoMarketSummaryStatusQuery query = new AutoMarketSummaryStatusQuery(jdbcTemplate, simulationClockService());
        jdbcTemplate.update("insert into stock_auto_market_config(symbol, enabled) values ('STOCK001', true)");
        jdbcTemplate.update("""
                insert into stock_auto_participant(
                    user_key, enabled, withdrawn_at, profile_type, recurring_cash_amount
                )
                values ('auto-001', true, null, 'NOISE_TRADER', null)
                """);
        jdbcTemplate.update("insert into stock_listing_auto_account_config(symbol) values ('STOCK001')");

        var response = query.getSummaryStatus(false, false);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(1L);
        assertThat(response.enabledParticipantCount()).isEqualTo(1L);
        assertThat(response.salaryEligibleParticipantCount()).isZero();
        assertThat(response.openAutoOrderCount()).isZero();
        assertThat(response.todayAutoExecutionCount()).isZero();
    }

    @Test
    void countSalaryEligibleAutoParticipants_readsCountWithJdbcClient() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("auto_market_summary_salary_count_test", true);
        AutoMarketSummaryStatusQuery query = new AutoMarketSummaryStatusQuery(jdbcTemplate, simulationClockService());
        seedSalaryRows(jdbcTemplate);

        assertThat(query.countSalaryEligibleAutoParticipants()).isEqualTo(2L);
        assertThat(query.getSummaryStatus(false, true).salaryEligibleParticipantCount()).isEqualTo(2L);
    }

    @Test
    void countSalaryEligibleAutoParticipants_excludesDividendReinvestorEvenWithCashPolicy() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("auto_market_summary_salary_dividend_excluded_test", true);
        AutoMarketSummaryStatusQuery query = new AutoMarketSummaryStatusQuery(jdbcTemplate, simulationClockService());
        seedSalaryRows(jdbcTemplate);
        jdbcTemplate.update("""
                insert into stock_auto_participant(
                    user_key, enabled, withdrawn_at, profile_type, recurring_cash_amount
                )
                values ('auto-dividend', true, null, 'DIVIDEND_REINVESTOR', ?)
                """, new BigDecimal("1000.00"));
        jdbcTemplate.update("""
                insert into stock_account(id, user_key, status) values (4, 'auto-dividend', 'ACTIVE')
                """);
        jdbcTemplate.update("""
                insert into stock_auto_participant_profile_config(profile_type, recurring_deposit_amount)
                values ('DIVIDEND_REINVESTOR', ?)
                """, new BigDecimal("500.00"));

        assertThat(query.countSalaryEligibleAutoParticipants()).isEqualTo(2L);
    }

    private JdbcTemplate createJdbcTemplate(String databaseName, boolean includeSalaryTables) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        jdbcTemplate.execute("""
                create table stock_auto_market_config (
                    symbol varchar(20) primary key,
                    enabled boolean not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant (
                    user_key varchar(64) primary key,
                    enabled boolean not null,
                    withdrawn_at timestamp,
                    profile_type varchar(40) not null,
                    recurring_cash_amount decimal(19, 2)
                )
                """);
        jdbcTemplate.execute("""
                create table stock_listing_auto_account_config (
                    symbol varchar(20) primary key
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order (
                    id bigint primary key,
                    account_id bigint not null,
                    market_type varchar(30) not null,
                    status varchar(30) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_execution (
                    id bigint primary key,
                    account_id bigint not null,
                    executed_at timestamp not null
                )
                """);
        if (!includeSalaryTables) {
            return jdbcTemplate;
        }
        jdbcTemplate.execute("""
                create table stock_account (
                    id bigint primary key,
                    user_key varchar(64) not null,
                    status varchar(20) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant_profile_config (
                    profile_type varchar(40) primary key,
                    recurring_deposit_amount decimal(19, 2)
                )
                """);
        return jdbcTemplate;
    }

    private SimulationClockService simulationClockService() {
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentMarketDayStart()).thenReturn(SimulationDayClock.currentDayStart());
        when(simulationClockService.currentMarketDateTime()).thenReturn(SimulationDayClock.currentDayStart().plusMinutes(25));
        return simulationClockService;
    }

    private void seedSummaryRows(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("insert into stock_auto_market_config(symbol, enabled) values ('STOCK001', true)");
        jdbcTemplate.update("insert into stock_auto_market_config(symbol, enabled) values ('STOCK002', false)");
        jdbcTemplate.update("insert into stock_auto_market_config(symbol, enabled) values ('STOCK003', true)");
        jdbcTemplate.update("insert into stock_listing_auto_account_config(symbol) values ('STOCK001')");
        jdbcTemplate.update("insert into stock_listing_auto_account_config(symbol) values ('STOCK002')");
        seedSalaryRows(jdbcTemplate);
        jdbcTemplate.update("insert into stock_order(id, account_id, market_type, status) values (1, 1, 'ORDER_BOOK', 'PENDING')");
        jdbcTemplate.update("insert into stock_order(id, account_id, market_type, status) values (2, 2, 'ORDER_BOOK', 'PARTIALLY_FILLED')");
        jdbcTemplate.update("insert into stock_order(id, account_id, market_type, status) values (3, 1, 'ORDER_BOOK', 'FILLED')");
        jdbcTemplate.update("insert into stock_order(id, account_id, market_type, status) values (4, 3, 'ORDER_BOOK', 'PENDING')");
        LocalDateTime simulationDayStart = SimulationDayClock.currentDayStart();
        jdbcTemplate.update("insert into stock_execution(id, account_id, executed_at) values (1, 1, ?)", simulationDayStart.plusMinutes(10));
        jdbcTemplate.update("insert into stock_execution(id, account_id, executed_at) values (2, 2, ?)", simulationDayStart.plusMinutes(20));
        jdbcTemplate.update("insert into stock_execution(id, account_id, executed_at) values (3, 3, ?)", simulationDayStart.plusMinutes(30));
        jdbcTemplate.update("insert into stock_execution(id, account_id, executed_at) values (4, 1, ?)", simulationDayStart.minusMinutes(1));
    }

    private void seedSalaryRows(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                insert into stock_auto_participant(
                    user_key, enabled, withdrawn_at, profile_type, recurring_cash_amount
                )
                values ('auto-001', true, null, 'PAYDAY_ACCUMULATOR', ?)
                """, new BigDecimal("1000.00"));
        jdbcTemplate.update("""
                insert into stock_auto_participant(
                    user_key, enabled, withdrawn_at, profile_type, recurring_cash_amount
                )
                values ('auto-002', true, null, 'NOISE_TRADER', null)
                """);
        jdbcTemplate.update("""
                insert into stock_auto_participant(
                    user_key, enabled, withdrawn_at, profile_type, recurring_cash_amount
                )
                values ('auto-003', false, null, 'NOISE_TRADER', ?)
                """, new BigDecimal("1000.00"));
        jdbcTemplate.update("""
                insert into stock_account(id, user_key, status) values (1, 'auto-001', 'ACTIVE')
                """);
        jdbcTemplate.update("""
                insert into stock_account(id, user_key, status) values (2, 'auto-002', 'ACTIVE')
                """);
        jdbcTemplate.update("""
                insert into stock_account(id, user_key, status) values (3, 'auto-003', 'ACTIVE')
                """);
        jdbcTemplate.update("""
                insert into stock_auto_participant_profile_config(profile_type, recurring_deposit_amount)
                values ('NOISE_TRADER', ?)
                """, new BigDecimal("500.00"));
    }
}
