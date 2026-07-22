package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.market.vo.CorporateActionSubscriptionRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.test.database.replace=none",
        "stock.schema-readiness.enabled=false"
})
@ActiveProfiles("test")
class CorporateActionSubscriptionMysqlConcurrencyTest {

    private static final LocalDate SIMULATION_DATE = LocalDate.of(2026, 7, 1);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36")
            .withDatabaseName("stock_subscription_test")
            .withUsername("stock_test")
            .withPassword("stock_test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("database.datasource.pub.master.url", MYSQL::getJdbcUrl);
        registry.add("database.datasource.pub.master.driver-class-name", MYSQL::getDriverClassName);
        registry.add("database.datasource.pub.master.username", MYSQL::getUsername);
        registry.add("database.datasource.pub.master.password", MYSQL::getPassword);
        registry.add("database.datasource.pub.slave1.url", MYSQL::getJdbcUrl);
        registry.add("database.datasource.pub.slave1.driver-class-name", MYSQL::getDriverClassName);
        registry.add("database.datasource.pub.slave1.username", MYSQL::getUsername);
        registry.add("database.datasource.pub.slave1.password", MYSQL::getPassword);
        registry.add("database.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private CorporateActionSubscriptionService subscriptionService;

    @Autowired
    private StockCorporateActionRepository corporateActionRepository;

    @Autowired
    private StockAccountRepository accountRepository;

    @Autowired
    private StockAccountCashFlowRepository cashFlowRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createLedgerGuardTables();
        jdbcTemplate.update("delete from stock_account_cash_flow");
        jdbcTemplate.update("delete from stock_corporate_action_entitlement");
        jdbcTemplate.update("delete from stock_corporate_action");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update("delete from stock_post_close_cycle");
        setSimulationClockAfterClose();
    }

    @Test
    void subscribe_publicOfferingConcurrentRequests_innoDbNeverOversubscribesOrDoubleDebits() throws Exception {
        StockAccount firstAccount = createFundedAccount("mysql-public-first", "1000.00");
        StockAccount secondAccount = createFundedAccount("mysql-public-second", "1000.00");
        StockCorporateAction action = createPublicOffering("MYSQLSUB01", 10L, "10.00");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(
                    () -> subscribeAfterStart(action.getId(), firstAccount.getUserKey(), start)
            );
            Future<Boolean> second = executor.submit(
                    () -> subscribeAfterStart(action.getId(), secondAccount.getUserKey(), start)
            );

            start.countDown();
            int successCount = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertThat(tuple(
                    successCount,
                    queryLong("select count(*) from stock_corporate_action_entitlement where action_id = ?", action.getId()),
                    queryLong("select coalesce(sum(subscribed_share_quantity), 0) from stock_corporate_action_entitlement where action_id = ?", action.getId()),
                    queryDecimal("select sum(cash_balance) from stock_account where id in (?, ?)", firstAccount.getId(), secondAccount.getId()),
                    queryLong("select count(*) from stock_account_cash_flow where reason = 'CAPITAL_INCREASE_SUBSCRIPTION'")
            )).isEqualTo(tuple(1, 1L, 10L, new BigDecimal("1900.00"), 1L));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void subscribe_shareholderConcurrentPartialRequests_innoDbAccumulatesWithinGrantedRights() throws Exception {
        StockAccount account = createFundedAccount("mysql-shareholder", "1000.00");
        StockCorporateAction action = createShareholderAllocation("MYSQLSUB02", 20L, "10.00");
        grantShareholderRights(action, account, 20L);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(
                    () -> subscribeAfterStart(action.getId(), account.getUserKey(), 10L, start)
            );
            Future<Boolean> second = executor.submit(
                    () -> subscribeAfterStart(action.getId(), account.getUserKey(), 10L, start)
            );

            start.countDown();
            int successCount = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertThat(tuple(
                    successCount,
                    queryLong("select subscribed_share_quantity from stock_corporate_action_entitlement where action_id = ?", action.getId()),
                    jdbcTemplate.queryForObject(
                            "select status from stock_corporate_action_entitlement where action_id = ?",
                            String.class,
                            action.getId()
                    ),
                    queryDecimal("select cash_balance from stock_account where id = ?", account.getId()),
                    queryLong("select count(*) from stock_account_cash_flow where reason = 'CAPITAL_INCREASE_SUBSCRIPTION'")
            )).isEqualTo(tuple(2, 20L, "SUBSCRIBED", new BigDecimal("800.00"), 2L));
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean subscribeAfterStart(long actionId, String userKey, CountDownLatch start) throws Exception {
        return subscribeAfterStart(actionId, userKey, 10L, start);
    }

    private boolean subscribeAfterStart(
            long actionId,
            String userKey,
            long shareQuantity,
            CountDownLatch start
    ) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            subscriptionService.subscribe(
                    actionId,
                    new CorporateActionSubscriptionRequest(shareQuantity),
                    userKey
            );
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private StockAccount createFundedAccount(String userKey, String cashAmount) {
        LocalDateTime openedAt = SIMULATION_DATE.atTime(20, 0);
        StockAccount account = StockAccount.open(userKey, null, null, null, openedAt);
        BigDecimal amount = new BigDecimal(cashAmount);
        account.depositCash(amount, openedAt);
        StockAccount saved = accountRepository.saveAndFlush(account);
        cashFlowRepository.saveAndFlush(StockAccountCashFlow.openingGrant(saved.getId(), amount, openedAt));
        return saved;
    }

    private StockCorporateAction createPublicOffering(String symbol, long shares, String issuePrice) {
        return corporateActionRepository.saveAndFlush(StockCorporateAction.paidInCapitalIncrease(
                symbol,
                StockCapitalIncreaseOfferingType.PUBLIC_OFFERING,
                shares,
                new BigDecimal(issuePrice),
                new BigDecimal("100.00"),
                null,
                null,
                SIMULATION_DATE,
                SIMULATION_DATE,
                SIMULATION_DATE.plusDays(1),
                SIMULATION_DATE.plusDays(2),
                "mysql public offering concurrency test",
                SIMULATION_DATE.minusDays(1).atTime(20, 0)
        ));
    }

    private StockCorporateAction createShareholderAllocation(String symbol, long shares, String issuePrice) {
        StockCorporateAction action = corporateActionRepository.saveAndFlush(StockCorporateAction.paidInCapitalIncrease(
                symbol,
                StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                shares,
                new BigDecimal(issuePrice),
                new BigDecimal("100.00"),
                new BigDecimal("55.00"),
                SIMULATION_DATE.minusDays(2),
                SIMULATION_DATE.minusDays(1),
                SIMULATION_DATE,
                SIMULATION_DATE,
                SIMULATION_DATE.plusDays(1),
                SIMULATION_DATE.plusDays(2),
                "mysql shareholder concurrency test",
                SIMULATION_DATE.minusDays(3).atTime(20, 0)
        ));
        jdbcTemplate.update(
                "update stock_corporate_action set status = 'EX_RIGHTS_APPLIED' where id = ?",
                action.getId()
        );
        return action;
    }

    private void grantShareholderRights(StockCorporateAction action, StockAccount account, long shareQuantity) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                    action_id, account_id, symbol, quantity, share_quantity, cash_amount,
                    subscribed_share_quantity, subscribed_cash_amount, forfeited_share_quantity,
                    status, holding_snapshot_run_id, created_at, subscribed_at, paid_at
                ) values (?, ?, ?, ?, ?, ?, null, null, 0, 'ANNOUNCED', null, ?, null, null)
                """,
                action.getId(),
                account.getId(),
                action.getSymbol(),
                shareQuantity,
                shareQuantity,
                action.getIssuePrice().multiply(BigDecimal.valueOf(shareQuantity)),
                SIMULATION_DATE.minusDays(1).atTime(20, 0)
        );
    }

    private void createLedgerGuardTables() {
        jdbcTemplate.execute(
                """
                create table if not exists stock_market_business_state (
                  state_id varchar(40) not null primary key,
                  active_business_date date not null,
                  preparing_business_date date null,
                  raw_simulation_date date not null,
                  version bigint not null default 0,
                  created_at datetime not null,
                  updated_at datetime not null
                ) engine=InnoDB
                """
        );
        jdbcTemplate.execute(
                """
                create table if not exists stock_post_close_cycle (
                  id bigint not null auto_increment primary key,
                  business_date date not null,
                  scope_type varchar(20) not null,
                  scope_key varchar(40) not null,
                  phase varchar(60) not null
                ) engine=InnoDB
                """
        );
    }

    private void setSimulationClockAfterClose() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("delete from stock_simulation_clock where clock_id = 'DEFAULT'");
        jdbcTemplate.update("delete from stock_market_business_state where state_id = 'DEFAULT'");
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at, last_heartbeat_at,
                    timezone, created_at, updated_at
                ) values ('DEFAULT', ?, 7200, 6000, false, null, null, 'Asia/Seoul', ?, ?)
                """,
                SIMULATION_DATE,
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date, raw_simulation_date,
                    version, created_at, updated_at
                ) values ('DEFAULT', ?, null, ?, 0, ?, ?)
                """,
                SIMULATION_DATE,
                SIMULATION_DATE,
                now,
                now
        );
    }

    private long queryLong(String sql, Object... parameters) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, parameters);
        return value == null ? 0L : value;
    }

    private BigDecimal queryDecimal(String sql, Object... parameters) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, parameters);
        return value == null ? BigDecimal.ZERO : value;
    }
}
