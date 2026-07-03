package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AdminFundFlowScope;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminFlowQueryServiceTest {

    private static final LocalDateTime SIMULATION_DAY_START = LocalDateTime.of(2026, 7, 3, 0, 0);
    private static final LocalDateTime SIMULATION_NOW = LocalDateTime.of(2026, 7, 3, 10, 0);

    @Test
    void getAdminFundFlowSummary_recentSimulationDay_readsScopedAggregateWithJdbcClient() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_fund_summary_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedFundFlow(jdbcTemplate);
        insertCashFlowAt(jdbcTemplate, 5L, 1L, "DEPOSIT", "1000.00", "ADMIN_DEPOSIT", SIMULATION_DAY_START.minusMinutes(1));
        insertExecutionAt(jdbcTemplate, 5L, 1L, "SELL", "1000.00", "1.00", "2.00", "1000.00", SIMULATION_DAY_START.minusMinutes(1));

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
    void getAdminFundFlowSummary_all_readsFullAggregateWithJdbcClient() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_fund_summary_all_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedFundFlow(jdbcTemplate);
        insertCashFlowAt(jdbcTemplate, 5L, 1L, "DEPOSIT", "1000.00", "ADMIN_DEPOSIT", SIMULATION_DAY_START.minusMinutes(1));
        insertExecutionAt(jdbcTemplate, 5L, 1L, "SELL", "1000.00", "1.00", "2.00", "1000.00", SIMULATION_DAY_START.minusMinutes(1));

        var summary = service.getAdminFundFlowSummary(AdminFundFlowScope.ALL);

        assertThat(summary.netExternalCashFlow()).isEqualByComparingTo(new BigDecimal("1380.00"));
        assertThat(summary.sellNetAmount()).isEqualByComparingTo(new BigDecimal("1900.00"));
        assertThat(summary.tradeNetCashFlow()).isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(summary.totalFeeAmount()).isEqualByComparingTo(new BigDecimal("11.00"));
        assertThat(summary.totalTaxAmount()).isEqualByComparingTo(new BigDecimal("7.00"));
        assertThat(summary.realizedProfit()).isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(summary.executionCount()).isEqualTo(3L);
    }

    @Test
    void getAdminSymbolFlows_recentSimulationDay_excludesOlderExecutions() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_symbol_flow_recent_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedSymbolFlow(jdbcTemplate);
        insertSymbolExecutionAt(jdbcTemplate, 1L, "STOCK001", "BUY", 3L, "300.00", "290.00", SIMULATION_DAY_START.minusMinutes(1));
        insertSymbolExecutionAt(jdbcTemplate, 2L, "STOCK001", "SELL", 2L, "200.00", "195.00", SIMULATION_NOW.minusMinutes(10));

        var response = service.getAdminSymbolFlows(0);

        assertThat(response.totalCount()).isEqualTo(1L);
        assertThat(response.symbolFlows()).hasSize(1);
        var symbolFlow = response.symbolFlows().getFirst();
        assertThat(symbolFlow.symbol()).isEqualTo("STOCK001");
        assertThat(symbolFlow.executionCount()).isEqualTo(1L);
        assertThat(symbolFlow.executionQuantity()).isEqualTo(2L);
        assertThat(symbolFlow.turnoverAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(symbolFlow.sellQuantity()).isEqualTo(2L);
        assertThat(symbolFlow.buyQuantity()).isZero();
        assertThat(symbolFlow.lastExecutedAt()).isEqualTo(SIMULATION_NOW.minusMinutes(10));
    }

    @Test
    void getAdminSymbolFlows_all_includesOlderExecutionsForCumulativeView() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_symbol_flow_all_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedSymbolFlow(jdbcTemplate);
        insertSymbolExecutionAt(jdbcTemplate, 1L, "STOCK001", "BUY", 3L, "300.00", "290.00", SIMULATION_DAY_START.minusMinutes(1));
        insertSymbolExecutionAt(jdbcTemplate, 2L, "STOCK001", "SELL", 2L, "200.00", "195.00", SIMULATION_NOW.minusMinutes(10));

        var response = service.getAdminSymbolFlows(0, AdminFundFlowScope.ALL);

        assertThat(response.totalCount()).isEqualTo(1L);
        assertThat(response.symbolFlows()).hasSize(1);
        var symbolFlow = response.symbolFlows().getFirst();
        assertThat(symbolFlow.executionCount()).isEqualTo(2L);
        assertThat(symbolFlow.executionQuantity()).isEqualTo(5L);
        assertThat(symbolFlow.turnoverAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(symbolFlow.buyQuantity()).isEqualTo(3L);
        assertThat(symbolFlow.sellQuantity()).isEqualTo(2L);
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
        when(simulationClockService.currentMarketDayStart()).thenReturn(SIMULATION_DAY_START);
        when(simulationClockService.currentMarketDateTime()).thenReturn(SIMULATION_NOW);
        return new AdminFlowQueryService(
                jdbcTemplate,
                new AdminSymbolFlowQueryService(jdbcTemplate, stockOrderBookInstrumentRepository, simulationClockService),
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
                    symbol varchar(20) not null default 'STOCK001',
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
                    current_price decimal(19, 2) not null,
                    previous_close decimal(19, 2) not null default 0
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_instrument (
                    symbol varchar(20) primary key,
                    name varchar(100) not null,
                    enabled boolean not null,
                    issued_shares bigint not null,
                    tradable_shares bigint not null,
                    initial_price decimal(19, 2) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_market_config (
                    symbol varchar(20) primary key,
                    market_status varchar(30) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_corporate_action (
                    id bigint primary key,
                    symbol varchar(20) not null,
                    status varchar(30) not null
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
                    source varchar(40) not null default 'INTERNAL_ORDER_BOOK',
                    quantity bigint not null default 1,
                    gross_amount decimal(19, 2) not null default 0,
                    net_amount decimal(19, 2) not null,
                    fee_amount decimal(19, 2) not null,
                    tax_amount decimal(19, 2) not null,
                    realized_profit decimal(19, 2) not null,
                    executed_at timestamp not null
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

    private void seedSymbolFlow(JdbcTemplate jdbcTemplate) {
        insertAccount(jdbcTemplate, 1L, "symbol-user-1", "ACTIVE", "1000.00");
        insertAccount(jdbcTemplate, 2L, "symbol-user-2", "ACTIVE", "2000.00");
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, enabled, issued_shares, tradable_shares, initial_price
                )
                values ('STOCK001', '테스트주식', true, 100000, 90000, ?)
                """,
                new BigDecimal("100.00")
        );
        jdbcTemplate.update(
                "insert into stock_order_book_market_config(symbol, market_status) values ('STOCK001', 'REGULAR')"
        );
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price, previous_close) values ('STOCK001', ?, ?)",
                new BigDecimal("110.00"),
                new BigDecimal("100.00")
        );
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
                SIMULATION_NOW.minusMinutes(minute)
        );
    }

    private void insertCashFlowAt(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            String flowType,
            String amount,
            String reason,
            LocalDateTime createdAt
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
                createdAt
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
                    id, account_id, symbol, side, net_amount, fee_amount, tax_amount, realized_profit, executed_at
                )
                values (?, ?, 'STOCK001', ?, ?, ?, ?, ?, ?)
                """,
                id,
                accountId,
                side,
                new BigDecimal(netAmount),
                new BigDecimal(feeAmount),
                new BigDecimal(taxAmount),
                new BigDecimal(realizedProfit),
                SIMULATION_NOW.minusMinutes(id)
        );
    }

    private void insertExecutionAt(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            String side,
            String netAmount,
            String feeAmount,
            String taxAmount,
            String realizedProfit,
            LocalDateTime executedAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    id, account_id, symbol, side, net_amount, fee_amount, tax_amount, realized_profit, executed_at
                )
                values (?, ?, 'STOCK001', ?, ?, ?, ?, ?, ?)
                """,
                id,
                accountId,
                side,
                new BigDecimal(netAmount),
                new BigDecimal(feeAmount),
                new BigDecimal(taxAmount),
                new BigDecimal(realizedProfit),
                executedAt
        );
    }

    private void insertSymbolExecutionAt(
            JdbcTemplate jdbcTemplate,
            long id,
            String symbol,
            String side,
            long quantity,
            String grossAmount,
            String netAmount,
            LocalDateTime executedAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    id, account_id, symbol, side, source, quantity, gross_amount, net_amount,
                    fee_amount, tax_amount, realized_profit, executed_at
                )
                values (?, 1, ?, ?, 'INTERNAL_ORDER_BOOK', ?, ?, ?, 0, 0, 0, ?)
                """,
                id,
                symbol,
                side,
                quantity,
                new BigDecimal(grossAmount),
                new BigDecimal(netAmount),
                executedAt
        );
    }
}
