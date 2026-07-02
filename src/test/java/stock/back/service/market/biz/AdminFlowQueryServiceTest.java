package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminFlowQueryServiceTest {

    @Test
    void getAdminFundFlowSummary_readsAggregateWithJdbcClient() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_fund_summary_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedFundFlow(jdbcTemplate);

        var summary = service.getAdminFundFlowSummary();

        assertThat(summary.activeAccountCount()).isEqualTo(2L);
        assertThat(summary.totalCashBalance()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(summary.totalReservedBuyCash()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(summary.totalHoldingMarketValue()).isEqualByComparingTo(new BigDecimal("260.00"));
        assertThat(summary.totalAsset()).isEqualByComparingTo(new BigDecimal("3410.00"));
        assertThat(summary.externalDepositAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(summary.externalWithdrawAmount()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(summary.netExternalCashFlow()).isEqualByComparingTo(new BigDecimal("380.00"));
        assertThat(summary.dividendIncomeAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(summary.buyNetAmount()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(summary.sellNetAmount()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(summary.tradeNetCashFlow()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(summary.totalFeeAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(summary.totalTaxAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(summary.realizedProfit()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(summary.executionCount()).isEqualTo(2L);
    }

    @Test
    void getAdminCashFlows_readsPageWithJdbcClient() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_cash_page_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        insertAccount(jdbcTemplate, 1L, "flow-user-1", "ACTIVE", "1000.00");
        insertAccount(jdbcTemplate, 2L, "flow-user-2", "ACTIVE", "2000.00");
        insertCashFlow(jdbcTemplate, 1L, 1L, "DEPOSIT", "100.00", "ADMIN_DEPOSIT", 0);
        insertCashFlow(jdbcTemplate, 2L, 2L, "WITHDRAW", "50.00", "ADMIN_WITHDRAW", 1);
        insertCashFlow(jdbcTemplate, 3L, 1L, "DEPOSIT", "30.00", "DIVIDEND_PAYMENT", 2);

        var page = service.getAdminCashFlows(0, 2);

        assertThat(page.totalElements()).isEqualTo(3L);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.hasPrevious()).isFalse();
        assertThat(page.hasNext()).isTrue();
        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).id()).isEqualTo(1L);
        assertThat(page.content().get(0).userKey()).isEqualTo("flow-user-1");
        assertThat(page.content().get(1).id()).isEqualTo(2L);
        assertThat(page.content().get(1).userKey()).isEqualTo("flow-user-2");
    }

    private AdminFlowQueryService createService(JdbcTemplate jdbcTemplate) {
        StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository = mock(StockOrderBookInstrumentRepository.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentMarketDayStart()).thenReturn(SimulationDayClock.currentDayStart());
        return new AdminFlowQueryService(
                jdbcTemplate,
                new AdminSymbolFlowQueryService(jdbcTemplate, stockOrderBookInstrumentRepository),
                simulationClockService
        );
    }

    private JdbcTemplate createJdbcTemplate(String databaseName) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        jdbcTemplate.execute("""
                create table stock_account (
                    id bigint primary key,
                    user_key varchar(100) not null,
                    status varchar(30) not null,
                    cash_balance decimal(19, 2) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order (
                    id bigint primary key,
                    account_id bigint not null,
                    market_type varchar(30) not null,
                    side varchar(10) not null,
                    status varchar(30) not null,
                    reserved_cash decimal(19, 2) not null,
                    quantity bigint not null,
                    filled_quantity bigint not null,
                    created_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_holding (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    average_price decimal(19, 2) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_price (
                    symbol varchar(20) primary key,
                    current_price decimal(19, 2) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_account_cash_flow (
                    id bigint primary key,
                    account_id bigint not null,
                    flow_type varchar(30) not null,
                    amount decimal(19, 2) not null,
                    reason varchar(100) not null,
                    created_by varchar(100) not null,
                    created_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_execution (
                    id bigint primary key,
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    side varchar(10) not null,
                    net_amount decimal(19, 2) not null,
                    fee_amount decimal(19, 2) not null,
                    tax_amount decimal(19, 2) not null,
                    realized_profit decimal(19, 2) not null
                )
                """);
        return jdbcTemplate;
    }

    private void seedFundFlow(JdbcTemplate jdbcTemplate) {
        insertAccount(jdbcTemplate, 1L, "active-user-1", "ACTIVE", "1000.00");
        insertAccount(jdbcTemplate, 2L, "active-user-2", "ACTIVE", "2000.00");
        insertAccount(jdbcTemplate, 3L, "closed-user", "CLOSED", "9999.00");
        insertOrder(jdbcTemplate, 1L, 1L, "BUY", "PENDING", "100.00");
        insertOrder(jdbcTemplate, 2L, 2L, "BUY", "PARTIALLY_FILLED", "50.00");
        insertOrder(jdbcTemplate, 3L, 1L, "SELL", "PENDING", "0.00");
        insertOrder(jdbcTemplate, 4L, 1L, "BUY", "FILLED", "999.00");
        insertPrice(jdbcTemplate, "STOCK001", "80.00");
        insertHolding(jdbcTemplate, 1L, "STOCK001", 2L, "70.00");
        insertHolding(jdbcTemplate, 2L, "STOCK002", 1L, "100.00");
        insertCashFlow(jdbcTemplate, 1L, 1L, "DEPOSIT", "500.00", "ADMIN_DEPOSIT", 3);
        insertCashFlow(jdbcTemplate, 2L, 1L, "WITHDRAW", "120.00", "ADMIN_WITHDRAW", 2);
        insertCashFlow(jdbcTemplate, 3L, 2L, "DEPOSIT", "30.00", "DIVIDEND_PAYMENT", 1);
        insertCashFlow(jdbcTemplate, 4L, 3L, "DEPOSIT", "9999.00", "ADMIN_DEPOSIT", 0);
        insertExecution(jdbcTemplate, 1L, 1L, "BUY", "700.00", "7.00", "0.00", "0.00");
        insertExecution(jdbcTemplate, 2L, 2L, "SELL", "900.00", "3.00", "5.00", "200.00");
        insertExecution(jdbcTemplate, 3L, 3L, "SELL", "9999.00", "1.00", "1.00", "9999.00");
    }

    private void insertAccount(JdbcTemplate jdbcTemplate, long id, String userKey, String status, String cashBalance) {
        jdbcTemplate.update(
                "insert into stock_account(id, user_key, status, cash_balance) values (?, ?, ?, ?)",
                id,
                userKey,
                status,
                new BigDecimal(cashBalance)
        );
    }

    private void insertOrder(JdbcTemplate jdbcTemplate, long id, long accountId, String side, String status, String reservedCash) {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, account_id, market_type, side, status, reserved_cash, quantity, filled_quantity, created_at
                )
                values (?, ?, 'ORDER_BOOK', ?, ?, ?, 1, 0, ?)
                """,
                id,
                accountId,
                side,
                status,
                new BigDecimal(reservedCash),
                LocalDateTime.now()
        );
    }

    private void insertPrice(JdbcTemplate jdbcTemplate, String symbol, String currentPrice) {
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price) values (?, ?)",
                symbol,
                new BigDecimal(currentPrice)
        );
    }

    private void insertHolding(JdbcTemplate jdbcTemplate, long accountId, String symbol, long quantity, String averagePrice) {
        jdbcTemplate.update(
                "insert into stock_holding(account_id, symbol, quantity, average_price) values (?, ?, ?, ?)",
                accountId,
                symbol,
                quantity,
                new BigDecimal(averagePrice)
        );
    }

    private void insertCashFlow(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            String flowType,
            String amount,
            String reason,
            long minute
    ) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(
                    id, account_id, flow_type, amount, reason, created_by, created_at
                )
                values (?, ?, ?, ?, ?, 'test-admin', ?)
                """,
                id,
                accountId,
                flowType,
                new BigDecimal(amount),
                reason,
                LocalDateTime.now().minusMinutes(minute)
        );
    }

    private void insertExecution(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            String side,
            String netAmount,
            String feeAmount,
            String taxAmount,
            String realizedProfit
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    id, account_id, symbol, side, net_amount, fee_amount, tax_amount, realized_profit
                )
                values (?, ?, 'STOCK001', ?, ?, ?, ?, ?)
                """,
                id,
                accountId,
                side,
                new BigDecimal(netAmount),
                new BigDecimal(feeAmount),
                new BigDecimal(taxAmount),
                new BigDecimal(realizedProfit)
        );
    }
}
