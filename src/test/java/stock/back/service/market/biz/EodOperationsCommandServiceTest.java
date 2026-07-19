package stock.back.service.market.biz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import stock.back.service.common.exception.StockException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EodOperationsCommandServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 15);
    private static final LocalDateTime FAILED_AT = BUSINESS_DATE.atTime(18, 12);
    private static final Pattern HOT_LEDGER_ACCESS = Pattern.compile(
            "(?i)\\b(?:from|join|update|into)\\s+(?:stock_order|stock_execution|stock_account|stock_holding)\\b"
    );

    @Test
    void retryFailedPhase_oldestClosedCycle_releasesOnlyCoordinatorBackoff() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        long cycleId = seedCycle(jdbcTemplate, BUSINESS_DATE, "FULL_MARKET", "ALL", "FAILED", null);
        seedAttempt(jdbcTemplate, cycleId, "FAILED");
        EodOperationsCommandService service = service(jdbcTemplate);

        var response = service.retryFailedPhase(cycleId, "admin-user");
        var stored = jdbcTemplate.queryForMap(
                "select status, next_retry_at, version, attempt_count, last_error_code from stock_post_close_cycle where id = ?",
                cycleId
        );

        assertThat(Arrays.asList(
                response.cycleId(),
                response.phase(),
                response.previousStatus(),
                response.status(),
                response.attemptCount(),
                response.requestedBy(),
                stored.get("STATUS"),
                stored.get("NEXT_RETRY_AT"),
                stored.get("VERSION"),
                stored.get("ATTEMPT_COUNT"),
                stored.get("LAST_ERROR_CODE")
        )).containsExactly(
                cycleId,
                "LEDGER_FROZEN",
                "FAILED",
                "PENDING",
                1,
                "admin-user",
                "PENDING",
                null,
                2L,
                1,
                "TEST_FAILURE"
        );
    }

    @Test
    void retryFailedPhase_openMarket_rejects() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        long cycleId = seedCycle(jdbcTemplate, BUSINESS_DATE, "FULL_MARKET", "ALL", "FAILED", null);
        jdbcTemplate.update(
                "insert into stock_order_book_market_config(symbol, enabled, market_status) values ('EOD001', true, 'OPEN')"
        );

        assertThatThrownBy(() -> service(jdbcTemplate).retryFailedPhase(cycleId, "admin-user"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("market is open");
    }

    @Test
    void retryFailedPhase_openSessionFence_rejectsInconsistentClosedConfig() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        long cycleId = seedCycle(jdbcTemplate, BUSINESS_DATE, "FULL_MARKET", "ALL", "FAILED", null);
        jdbcTemplate.update(
                "insert into stock_order_book_market_config(symbol, enabled, market_status) values ('EOD001', true, 'CLOSED')"
        );
        jdbcTemplate.update(
                """
                insert into stock_market_session_fence(market_type, symbol, session_state)
                values ('ORDER_BOOK', 'EOD001', 'OPEN')
                """
        );

        assertThatThrownBy(() -> service(jdbcTemplate).retryFailedPhase(cycleId, "admin-user"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("market is open");
    }

    @Test
    void retryFailedPhase_newerCycle_rejectsOutOfOrderRetry() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        seedCycle(jdbcTemplate, BUSINESS_DATE.minusDays(1), "FULL_MARKET", "ALL", "FAILED", null);
        long newerCycleId = seedCycle(jdbcTemplate, BUSINESS_DATE, "FULL_MARKET", "ALL", "FAILED", null);

        assertThatThrownBy(() -> service(jdbcTemplate).retryFailedPhase(newerCycleId, "admin-user"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("oldest incomplete");
    }

    @Test
    void retryFailedPhase_deferredCycle_doesNotBypassPolicyEligibility() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        long cycleId = seedCycle(jdbcTemplate, BUSINESS_DATE, "FULL_MARKET", "ALL", "DEFERRED", null);

        assertThatThrownBy(() -> service(jdbcTemplate).retryFailedPhase(cycleId, "admin-user"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void retryFailedPhase_runningAttempt_rejectsInconsistentOwnership() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        long cycleId = seedCycle(jdbcTemplate, BUSINESS_DATE, "FULL_MARKET", "ALL", "FAILED", null);
        seedAttempt(jdbcTemplate, cycleId, "RUNNING");

        assertThatThrownBy(() -> service(jdbcTemplate).retryFailedPhase(cycleId, "admin-user"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("active execution owner");
    }

    @Test
    void retryCommandSource_neverReadsOrWritesHotLedgers() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/stock/back/service/market/biz/EodOperationsCommandService.java"),
                StandardCharsets.UTF_8
        );

        assertThat(HOT_LEDGER_ACCESS.matcher(source).find()).isFalse();
    }

    private EodOperationsCommandService service(JdbcTemplate jdbcTemplate) {
        return new EodOperationsCommandService(JdbcClient.create(jdbcTemplate));
    }

    private long seedCycle(
            JdbcTemplate jdbcTemplate,
            LocalDate businessDate,
            String scopeType,
            String scopeKey,
            String status,
            String ownerId
    ) {
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(
                    business_date, scope_type, scope_key, phase, status, version,
                    attempt_count, owner_id, lease_until, next_retry_at,
                    last_error_code, last_error_message, created_at, updated_at
                ) values (?, ?, ?, 'LEDGER_FROZEN', ?, 1, 1, ?, null, ?,
                          'TEST_FAILURE', 'test failure', ?, ?)
                """,
                businessDate,
                scopeType,
                scopeKey,
                status,
                ownerId,
                FAILED_AT.plusMinutes(5),
                FAILED_AT,
                FAILED_AT
        );
        return jdbcTemplate.queryForObject("select max(id) from stock_post_close_cycle", Long.class);
    }

    private void seedAttempt(JdbcTemplate jdbcTemplate, long cycleId, String status) {
        jdbcTemplate.update(
                """
                insert into stock_post_close_phase_attempt(cycle_id, status)
                values (?, ?)
                """,
                cycleId,
                status
        );
    }

    private JdbcTemplate createJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:eod-command-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                """
                create table stock_post_close_cycle(
                    id bigint generated by default as identity primary key,
                    business_date date not null,
                    scope_type varchar(20) not null,
                    scope_key varchar(40) not null,
                    phase varchar(60) not null,
                    status varchar(20) not null,
                    version bigint not null,
                    attempt_count int not null,
                    owner_id varchar(128),
                    lease_until timestamp,
                    next_retry_at timestamp,
                    last_error_code varchar(80),
                    last_error_message varchar(1000),
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_post_close_phase_attempt(
                    id bigint generated by default as identity primary key,
                    cycle_id bigint not null,
                    status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_order_book_market_config(
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_virtual_market_config(
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_market_session_fence(
                    market_type varchar(20) not null,
                    symbol varchar(20) not null,
                    session_state varchar(20) not null,
                    primary key(market_type, symbol)
                )
                """
        );
        return jdbcTemplate;
    }
}
