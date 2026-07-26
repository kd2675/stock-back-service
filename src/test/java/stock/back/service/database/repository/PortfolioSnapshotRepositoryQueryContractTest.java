package stock.back.service.database.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioSnapshotRepositoryQueryContractTest {

    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        JdbcTemplate schema = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:portfolio_snapshot_repository_%d;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
                        .formatted(System.nanoTime()),
                "sa",
                ""
        ));
        schema.execute("""
                create table stock_post_close_cycle (
                    id bigint primary key,
                    scope_type varchar(20) not null,
                    scope_key varchar(40) not null,
                    phase varchar(60) not null
                )
                """);
        schema.execute("""
                create table portfolio_snapshot (
                    id bigint primary key,
                    close_cycle_id bigint,
                    close_run_id bigint,
                    account_id bigint not null,
                    snapshot_date date not null,
                    return_rate decimal(19, 8),
                    return_rate_status varchar(40) not null default 'DEFINED'
                )
                """);
        schema.execute("""
                create table stock_account (
                    id bigint primary key,
                    participant_category varchar(30) not null
                )
                """);
        schema.execute("""
                create table stock_close_account_snapshot (
                    close_cycle_id bigint not null,
                    account_id bigint not null,
                    participant_category varchar(30) not null,
                    primary key (close_cycle_id, account_id)
                )
                """);
        schema.update("insert into stock_post_close_cycle values (10, 'FULL_MARKET', 'ALL', 'LEDGER_FROZEN')");
        schema.update("insert into stock_post_close_cycle values (11, 'FULL_MARKET', 'ALL', 'PORTFOLIO_SETTLED')");
        schema.update("insert into stock_post_close_cycle values (12, 'SYMBOL', 'DEMO001', 'PORTFOLIO_SETTLED')");
        schema.update("insert into stock_account values (1, 'MANUAL_PARTICIPANT')");
        schema.update("insert into stock_account values (2, 'INSTITUTIONAL_INVESTOR')");
        schema.update("insert into stock_account values (3, 'AUTO_PARTICIPANT')");
        schema.update("insert into stock_close_account_snapshot values (10, 1, 'MANUAL_PARTICIPANT')");
        schema.update("insert into stock_close_account_snapshot values (11, 1, 'MANUAL_PARTICIPANT')");
        schema.update("insert into stock_close_account_snapshot values (11, 2, 'INSTITUTIONAL_INVESTOR')");
        schema.update("insert into stock_close_account_snapshot values (11, 3, 'AUTO_PARTICIPANT')");
        schema.update("insert into stock_close_account_snapshot values (12, 1, 'MANUAL_PARTICIPANT')");
        schema.update("insert into portfolio_snapshot values (1, null, null, 1, '2026-01-01', 1.0000, 'DEFINED')");
        schema.update("insert into portfolio_snapshot values (2, 10, 100, 1, '2026-01-03', 100.0000, 'DEFINED')");
        schema.update("insert into portfolio_snapshot values (3, 11, 101, 1, '2026-01-02', 2.0000, 'DEFINED')");
        schema.update("insert into portfolio_snapshot values (4, 12, 102, 1, '2026-01-04', 200.0000, 'DEFINED')");
        schema.update("insert into portfolio_snapshot values (5, 11, 101, 2, '2026-01-02', 3.0000, 'DEFINED')");
        schema.update("insert into portfolio_snapshot values (6, 11, 101, 3, '2026-01-02', 2.5000, 'DEFINED')");
        jdbcTemplate = new NamedParameterJdbcTemplate(schema);
    }

    @Test
    void accountHistoryQuery_partialOrWrongScopeCycle_excludesRows() throws Exception {
        String sql = repositorySql(
                "findTop30ByAccountIdOrderBySnapshotDateDesc",
                Long.class
        );

        List<Long> ids = queryIds(sql, new MapSqlParameterSource("accountId", 1L));

        assertThat(ids).containsExactly(3L, 1L);
    }

    @Test
    void rankingQuery_settledAndLegacyRows_ordersVisibleRowsOnly() throws Exception {
        String sql = repositorySql(
                "findTop20BySnapshotDateOrderByReturnRateDesc",
                LocalDate.class
        );

        List<Long> ids = queryIds(
                sql,
                new MapSqlParameterSource("snapshotDate", LocalDate.of(2026, 1, 2))
        );

        assertThat(ids).containsExactly(6L, 3L);
    }

    @Test
    void latestDateQuery_partialNewerCycle_keepsLatestFinalizedSnapshot() throws Exception {
        String sql = repositorySql("findTopRankingEligibleByOrderBySnapshotDateDesc");

        List<Long> ids = queryIds(sql, new MapSqlParameterSource());

        assertThat(ids).containsExactly(6L);
    }

    private List<Long> queryIds(String sql, MapSqlParameterSource parameters) {
        return jdbcTemplate.query(sql, parameters, (rs, rowNum) -> rs.getLong("id"));
    }

    private String repositorySql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = PortfolioSnapshotRepository.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(Query.class).value();
    }
}
