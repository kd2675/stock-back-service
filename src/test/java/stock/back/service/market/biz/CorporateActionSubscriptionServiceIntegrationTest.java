package stock.back.service.market.biz;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlementStatus;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.market.vo.CorporateActionSubscriptionRequest;
import stock.back.service.trading.biz.TradingService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@ActiveProfiles("test")
class CorporateActionSubscriptionServiceIntegrationTest {

    private static final LocalDate SIMULATION_DATE = LocalDate.of(2026, 7, 1);

    @Autowired
    private CorporateActionSubscriptionService corporateActionSubscriptionService;

    @Autowired
    private StockCorporateActionRepository stockCorporateActionRepository;

    @Autowired
    private StockAccountRepository stockAccountRepository;

    @Autowired
    private StockAccountCashFlowRepository stockAccountCashFlowRepository;

    @Autowired
    private TradingService tradingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanTestData();
        setSimulationClockAfterClose();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    @Test
    void subscribe_shareholderAllocation_returnsSubscribedStateAndKeepsEscrowInTotalAssets() {
        StockAccount account = createFundedAccount("sub-shareholder", "1000.00");
        StockCorporateAction action = createShareholderAllocation("ZQSUB01", 100L, "10.00");
        createShareholderEntitlement(action.getId(), account.getId(), action.getSymbol(), 20L);

        var response = corporateActionSubscriptionService.subscribe(
                action.getId(),
                new CorporateActionSubscriptionRequest(10L),
                account.getUserKey()
        );
        var portfolio = tradingService.getPortfolio(account.getUserKey());
        var fundFlow = tradingService.getFundFlow(account.getUserKey());

        assertThat(tuple(
                response.status(),
                response.subscribedShareQuantity(),
                response.subscribedCashAmount(),
                cashBalance(account.getUserKey()),
                subscriptionCashFlowAmount(account.getId()),
                portfolio.reservedBuyCash(),
                portfolio.totalAsset(),
                portfolio.returnRate(),
                fundFlow.externalWithdrawAmount(),
                fundFlow.netExternalCashFlow()
        )).isEqualTo(tuple(
                StockCorporateActionEntitlementStatus.SUBSCRIBED,
                10L,
                new BigDecimal("100.00"),
                new BigDecimal("900.00"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("0.0000"),
                BigDecimal.ZERO,
                new BigDecimal("1000.00")
        ));
    }

    @Test
    void subscribe_publicOfferingConcurrentRequests_neverOversubscribesOrDoubleDebits() throws Exception {
        StockAccount firstAccount = createFundedAccount("sub-public-first", "1000.00");
        StockAccount secondAccount = createFundedAccount("sub-public-second", "1000.00");
        StockCorporateAction action = createPublicOffering("ZQSUB02", 10L, "10.00");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> subscribeAfterStart(action.getId(), firstAccount.getUserKey(), start));
            Future<Boolean> second = executor.submit(() -> subscribeAfterStart(action.getId(), secondAccount.getUserKey(), start));

            start.countDown();
            int successCount = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertThat(tuple(
                    successCount,
                    entitlementCount(action.getId()),
                    subscribedShareQuantity(action.getId()),
                    totalCashBalance(firstAccount.getId(), secondAccount.getId()),
                    subscriptionCashFlowCount(firstAccount.getId(), secondAccount.getId())
            )).isEqualTo(tuple(1, 1L, 10L, new BigDecimal("1900.00"), 1L));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void subscribe_shareholderAllocationAboveRight_doesNotDebitCash() {
        StockAccount account = createFundedAccount("sub-right-limit", "1000.00");
        StockCorporateAction action = createShareholderAllocation("ZQSUB03", 100L, "10.00");
        createShareholderEntitlement(action.getId(), account.getId(), action.getSymbol(), 5L);

        String errorMessage;
        try {
            corporateActionSubscriptionService.subscribe(
                    action.getId(),
                    new CorporateActionSubscriptionRequest(6L),
                    account.getUserKey()
            );
            errorMessage = null;
        } catch (RuntimeException ex) {
            errorMessage = ex.getMessage();
        }

        assertThat(tuple(
                errorMessage,
                cashBalance(account.getUserKey()),
                subscriptionCashFlowCount(account.getId()),
                entitlementStatus(action.getId(), account.getId())
        )).isEqualTo(tuple(
                "Subscription share quantity exceeds allocated shareholder rights",
                new BigDecimal("1000.00"),
                0L,
                "ANNOUNCED"
        ));
    }

    @Test
    void subscribe_publicOfferingWithInsufficientCash_doesNotCreateEntitlementOrCashFlow() {
        StockAccount account = createFundedAccount("sub-insufficient-cash", "50.00");
        StockCorporateAction action = createPublicOffering("ZQSUB04", 10L, "10.00");

        String errorMessage;
        try {
            corporateActionSubscriptionService.subscribe(
                    action.getId(),
                    new CorporateActionSubscriptionRequest(10L),
                    account.getUserKey()
            );
            errorMessage = null;
        } catch (RuntimeException ex) {
            errorMessage = ex.getMessage();
        }

        assertThat(tuple(
                errorMessage,
                cashBalance(account.getUserKey()),
                entitlementCount(action.getId()),
                subscriptionCashFlowCount(account.getId())
        )).isEqualTo(tuple(
                "Insufficient cash balance for capital increase subscription",
                new BigDecimal("50.00"),
                0L,
                0L
        ));
    }

    @Test
    void subscribe_publicOfferingAfterWindow_doesNotDebitCash() {
        StockAccount account = createFundedAccount("sub-window-closed", "1000.00");
        StockCorporateAction action = createPublicOffering("ZQSUB05", 10L, "10.00");
        jdbcTemplate.update(
                "update stock_corporate_action set subscription_start_date = ?, subscription_end_date = ? where id = ?",
                SIMULATION_DATE.minusDays(2),
                SIMULATION_DATE.minusDays(1),
                action.getId()
        );

        String errorMessage;
        try {
            corporateActionSubscriptionService.subscribe(
                    action.getId(),
                    new CorporateActionSubscriptionRequest(1L),
                    account.getUserKey()
            );
            errorMessage = null;
        } catch (RuntimeException ex) {
            errorMessage = ex.getMessage();
        }

        assertThat(tuple(
                errorMessage,
                cashBalance(account.getUserKey()),
                entitlementCount(action.getId()),
                subscriptionCashFlowCount(account.getId())
        )).isEqualTo(tuple(
                "Capital increase subscription is already closed",
                new BigDecimal("1000.00"),
                0L,
                0L
        ));
    }

    private boolean subscribeAfterStart(long actionId, String userKey, CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            corporateActionSubscriptionService.subscribe(
                    actionId,
                    new CorporateActionSubscriptionRequest(10L),
                    userKey
            );
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private StockAccount createFundedAccount(String userKey, String cashAmount) {
        StockAccount account = StockAccount.open(userKey, null, null, null, SIMULATION_DATE.atTime(20, 0));
        BigDecimal amount = new BigDecimal(cashAmount);
        account.depositCash(amount, SIMULATION_DATE.atTime(20, 0));
        StockAccount savedAccount = stockAccountRepository.saveAndFlush(account);
        stockAccountCashFlowRepository.saveAndFlush(StockAccountCashFlow.openingGrant(
                savedAccount.getId(),
                amount,
                SIMULATION_DATE.atTime(20, 0)
        ));
        return savedAccount;
    }

    private StockCorporateAction createShareholderAllocation(String symbol, long shares, String issuePrice) {
        StockCorporateAction action = stockCorporateActionRepository.saveAndFlush(StockCorporateAction.paidInCapitalIncrease(
                symbol,
                StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                shares,
                new BigDecimal(issuePrice),
                new BigDecimal("100.00"),
                new BigDecimal("90.00"),
                SIMULATION_DATE.minusDays(1),
                SIMULATION_DATE,
                SIMULATION_DATE,
                SIMULATION_DATE.plusDays(1),
                SIMULATION_DATE.plusDays(2),
                "shareholder allocation test",
                SIMULATION_DATE.minusDays(2).atTime(20, 0)
        ));
        jdbcTemplate.update(
                "update stock_corporate_action set status = 'EX_RIGHTS_APPLIED' where id = ?",
                action.getId()
        );
        return action;
    }

    private StockCorporateAction createPublicOffering(String symbol, long shares, String issuePrice) {
        return stockCorporateActionRepository.saveAndFlush(StockCorporateAction.paidInCapitalIncrease(
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
                "public offering test",
                SIMULATION_DATE.minusDays(1).atTime(20, 0)
        ));
    }

    private void createShareholderEntitlement(
            long actionId,
            long accountId,
            String symbol,
            long allocatedShares
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount,
                  subscribed_share_quantity, subscribed_cash_amount, status,
                  holding_snapshot_run_id, created_at, subscribed_at, paid_at
                ) values (?, ?, ?, 100, ?, ?, null, null, 'ANNOUNCED', null, ?, null, null)
                """,
                actionId,
                accountId,
                symbol,
                allocatedShares,
                BigDecimal.valueOf(allocatedShares).multiply(new BigDecimal("10.00")),
                SIMULATION_DATE.minusDays(1).atTime(20, 0)
        );
    }

    private void setSimulationClockAfterClose() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                merge into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at, last_heartbeat_at,
                    timezone, created_at, updated_at
                ) key(clock_id)
                values ('DEFAULT', ?, 7200, 6000, false, null, null, 'Asia/Seoul', ?, ?)
                """,
                SIMULATION_DATE,
                now,
                now
        );
    }

    private BigDecimal cashBalance(String userKey) {
        return jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where user_key = ?",
                BigDecimal.class,
                userKey
        );
    }

    private BigDecimal subscriptionCashFlowAmount(long accountId) {
        return jdbcTemplate.queryForObject(
                """
                select amount
                  from stock_account_cash_flow
                 where account_id = ?
                   and reason = 'CAPITAL_INCREASE_SUBSCRIPTION'
                """,
                BigDecimal.class,
                accountId
        );
    }

    private long entitlementCount(long actionId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from stock_corporate_action_entitlement where action_id = ?",
                Long.class,
                actionId
        );
    }

    private long subscribedShareQuantity(long actionId) {
        return jdbcTemplate.queryForObject(
                """
                select coalesce(sum(subscribed_share_quantity), 0)
                  from stock_corporate_action_entitlement
                 where action_id = ?
                   and status = 'SUBSCRIBED'
                """,
                Long.class,
                actionId
        );
    }

    private BigDecimal totalCashBalance(long firstAccountId, long secondAccountId) {
        return jdbcTemplate.queryForObject(
                "select sum(cash_balance) from stock_account where id in (?, ?)",
                BigDecimal.class,
                firstAccountId,
                secondAccountId
        );
    }

    private long subscriptionCashFlowCount(long... accountIds) {
        if (accountIds.length == 1) {
            return jdbcTemplate.queryForObject(
                    """
                    select count(*)
                      from stock_account_cash_flow
                     where account_id = ?
                       and reason = 'CAPITAL_INCREASE_SUBSCRIPTION'
                    """,
                    Long.class,
                    accountIds[0]
            );
        }
        return jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_account_cash_flow
                 where account_id in (?, ?)
                   and reason = 'CAPITAL_INCREASE_SUBSCRIPTION'
                """,
                Long.class,
                accountIds[0],
                accountIds[1]
        );
    }

    private String entitlementStatus(long actionId, long accountId) {
        return jdbcTemplate.queryForObject(
                """
                select status
                  from stock_corporate_action_entitlement
                 where action_id = ?
                   and account_id = ?
                """,
                String.class,
                actionId,
                accountId
        );
    }

    private void cleanTestData() {
        jdbcTemplate.update("delete from stock_corporate_action_entitlement where symbol like 'ZQSUB%'");
        jdbcTemplate.update("delete from stock_corporate_action where symbol like 'ZQSUB%'");
        jdbcTemplate.update(
                """
                delete from stock_account_cash_flow
                 where account_id in (
                       select id from stock_account where user_key like 'sub-%'
                 )
                """
        );
        jdbcTemplate.update("delete from stock_account where user_key like 'sub-%'");
    }
}
