package stock.back.service.trading.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class AutoParticipantFundingBudgetReleaseServiceTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private AutoParticipantFundingBudgetReleaseService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:back_funding_release_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbcTemplate.execute("""
                create table stock_auto_participant_funding_budget (
                    id bigint primary key,
                    available_amount decimal(19,2) not null,
                    reserved_amount decimal(19,2) not null,
                    spent_amount decimal(19,2) not null,
                    expires_business_date date,
                    status varchar(20) not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant_order_budget (
                    order_id bigint not null,
                    budget_id bigint not null,
                    remaining_reserved_amount decimal(19,2) not null,
                    spent_amount decimal(19,2) not null,
                    released_amount decimal(19,2) not null,
                    updated_at timestamp not null,
                    primary key(order_id, budget_id)
                )
                """);
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_funding_budget(
                    id, available_amount, reserved_amount, spent_amount,
                    expires_business_date, status, updated_at
                ) values (1, 0, 150, 50, ?, 'ACTIVE', ?)
                """,
                LocalDate.of(2027, 1, 20),
                LocalDateTime.of(2027, 1, 18, 9, 0)
        );
        insertAllocation(101L, "100.00");
        insertAllocation(102L, "50.00");
        service = new AutoParticipantFundingBudgetReleaseService(jdbcTemplate);
    }

    @Test
    void releaseCancelledOrderBudgets_reconcilesAllocationAndBudgetExactlyAndIsIdempotent() {
        LocalDateTime releasedAt = LocalDateTime.of(2027, 1, 18, 10, 0);

        int first = transactionTemplate.execute(status ->
                service.releaseCancelledOrderBudgets(List.of(102L, 101L, 101L), releasedAt)
        );
        int second = transactionTemplate.execute(status ->
                service.releaseCancelledOrderBudgets(List.of(101L, 102L), releasedAt.plusSeconds(1))
        );

        assertThat(first).isEqualTo(2);
        assertThat(second).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select available_amount from stock_auto_participant_funding_budget where id = 1",
                BigDecimal.class
        )).isEqualByComparingTo("150.00");
        assertThat(jdbcTemplate.queryForObject(
                "select reserved_amount from stock_auto_participant_funding_budget where id = 1",
                BigDecimal.class
        )).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject(
                "select sum(released_amount) from stock_auto_participant_order_budget where budget_id = 1",
                BigDecimal.class
        )).isEqualByComparingTo("150.00");
    }

    @Test
    void releaseCancelledOrderBudgets_afterExpiry_marksBudgetExpired() {
        LocalDateTime releasedAt = LocalDateTime.of(2027, 1, 21, 0, 1);

        transactionTemplate.executeWithoutResult(status ->
                service.releaseCancelledOrderBudgets(List.of(101L, 102L), releasedAt)
        );

        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_auto_participant_funding_budget where id = 1",
                String.class
        )).isEqualTo("EXPIRED");
    }

    @Test
    void releaseCancelledOrderBudgets_overBoundedChunk_rejectsBeforeQuery() {
        List<Long> orderIds = LongStream.rangeClosed(1, 501).boxed().toList();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.releaseCancelledOrderBudgets(orderIds, LocalDateTime.of(2027, 1, 18, 10, 0))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 500");
    }

    private void insertAllocation(long orderId, String amount) {
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_order_budget(
                    order_id, budget_id, remaining_reserved_amount,
                    spent_amount, released_amount, updated_at
                ) values (?, 1, ?, 0, 0, ?)
                """,
                orderId,
                new BigDecimal(amount),
                LocalDateTime.of(2027, 1, 18, 9, 0)
        );
    }
}
