package stock.back.service.trading.biz;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.market.biz.SimulationClockService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountOrderCleanupServiceTest {

    @Test
    void cancelOpenOrdersForDetach_releasesBuyCashAndSellReservationsThenCancelsOpenOrders() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        StockHoldingRepository stockHoldingRepository = mock(StockHoldingRepository.class);
        AccountOrderCleanupService cleanupService = createCleanupService(stockHoldingRepository, jdbcTemplate);
        StockAccount account = StockAccount.open("cleanup-user");
        ReflectionTestUtils.setField(account, "id", 10L);
        account.depositCash(new BigDecimal("1000.00"));
        account.reserveCash(new BigDecimal("140.00"));
        StockHolding holding = holding(10L, "STOCK001", 10L, 5L, "70000.00");
        when(stockHoldingRepository.findByAccountIdAndSymbolsForUpdate(10L, List.of("STOCK001")))
                .thenReturn(List.of(holding));
        insertOrder(jdbcTemplate, 10L, "BUY", "PENDING", "STOCK001", 2L, 0L, "100.00", 1L);
        insertOrder(jdbcTemplate, 10L, "BUY", "PARTIALLY_FILLED", "STOCK001", 2L, 1L, "40.00", 2L);
        insertOrder(jdbcTemplate, 10L, "BUY", "FILLED", "STOCK001", 1L, 1L, "20.00", 3L);
        insertOrder(jdbcTemplate, 10L, "SELL", "PENDING", "STOCK001", 4L, 1L, "0.00", 4L);
        insertOrder(jdbcTemplate, 10L, "SELL", "PARTIALLY_FILLED", "STOCK001", 3L, 2L, "0.00", 5L);
        insertOrder(jdbcTemplate, 20L, "BUY", "PENDING", "STOCK001", 1L, 0L, "999.00", 6L);

        cleanupService.cancelOpenOrdersForDetach(account);

        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(holding.getReservedQuantity()).isEqualTo(1L);
        assertThat(count(jdbcTemplate, "select count(*) from stock_order where account_id = 10 and status = 'CANCELLED'"))
                .isEqualTo(4L);
        assertThat(count(jdbcTemplate, "select count(*) from stock_order where account_id = 10 and status = 'FILLED'"))
                .isEqualTo(1L);
        assertThat(decimal(jdbcTemplate, "select coalesce(sum(reserved_cash), 0) from stock_order where account_id = 10 and status = 'CANCELLED'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(count(jdbcTemplate, "select count(*) from stock_order where account_id = 20 and status = 'PENDING'"))
                .isEqualTo(1L);
    }

    @Test
    void cancelOpenOrderBookOrders_keepsVirtualPriceOrdersOpen() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        StockHoldingRepository stockHoldingRepository = mock(StockHoldingRepository.class);
        AccountOrderCleanupService cleanupService = createCleanupService(stockHoldingRepository, jdbcTemplate);
        StockAccount account = StockAccount.open("cleanup-order-book-user");
        ReflectionTestUtils.setField(account, "id", 10L);
        account.depositCash(new BigDecimal("1000.00"));
        account.reserveCash(new BigDecimal("300.00"));
        insertOrder(jdbcTemplate, 10L, "ORDER_BOOK", "BUY", "PENDING", "STOCK001", 1L, 0L, "100.00", 1L);
        insertOrder(jdbcTemplate, 10L, "VIRTUAL_PRICE", "BUY", "PENDING", "STOCK001", 1L, 0L, "200.00", 2L);

        cleanupService.cancelOpenOrderBookOrders(account);

        assertThat(account.getCashBalance()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(count(jdbcTemplate, "select count(*) from stock_order where id = 1 and status = 'CANCELLED'"))
                .isEqualTo(1L);
        assertThat(count(jdbcTemplate, "select count(*) from stock_order where id = 2 and status = 'PENDING'"))
                .isEqualTo(1L);
    }

    @Test
    void cancelOpenOrderBookOrders_lockedAccountPath_cancelsOrdersBeforeRefundingCash() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        StockHoldingRepository stockHoldingRepository = mock(StockHoldingRepository.class);
        AccountOrderCleanupService cleanupService = createCleanupService(stockHoldingRepository, jdbcTemplate);
        StockHolding holding = holding(10L, "STOCK001", 10L, 4L, "70000.00");
        when(stockHoldingRepository.findByAccountIdAndSymbolsForUpdate(10L, List.of("STOCK001")))
                .thenReturn(List.of(holding));
        StockAccount account = StockAccount.open("cleanup-locked-account");
        ReflectionTestUtils.setField(account, "id", 10L);
        account.depositCash(new BigDecimal("800.00"));
        account.reserveCash(new BigDecimal("100.00"));
        insertOrder(jdbcTemplate, 10L, "ORDER_BOOK", "BUY", "PENDING", "STOCK001", 1L, 0L, "100.00", 1L);
        insertOrder(jdbcTemplate, 10L, "ORDER_BOOK", "SELL", "PENDING", "STOCK001", 3L, 1L, "0.00", 2L);

        cleanupService.cancelOpenOrderBookOrders(account);

        assertThat(account.getCashBalance())
                .isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(holding.getReservedQuantity()).isEqualTo(2L);
        assertThat(count(jdbcTemplate, "select count(*) from stock_order where account_id = 10 and status = 'CANCELLED'"))
                .isEqualTo(2L);
        assertThat(decimal(jdbcTemplate, "select coalesce(sum(reserved_cash), 0) from stock_order where account_id = 10"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void cancelOpenOrderBookOrders_sameCreatedAtAcrossChunks_cancelsEveryCandidate() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        StockHoldingRepository stockHoldingRepository = mock(StockHoldingRepository.class);
        AccountOrderCleanupService cleanupService = createCleanupService(stockHoldingRepository, jdbcTemplate);
        StockAccount account = StockAccount.open("cleanup-many-orders");
        ReflectionTestUtils.setField(account, "id", 10L);
        account.depositCash(new BigDecimal("501.00"));
        account.reserveCash(new BigDecimal("501.00"));
        for (long id = 1L; id <= 501L; id++) {
            insertOrder(jdbcTemplate, 10L, "ORDER_BOOK", "BUY", "PENDING", "STOCK001", 1L, 0L, "1.00", id);
        }

        cleanupService.cancelOpenOrderBookOrders(account);

        assertThat(count(jdbcTemplate, "select count(*) from stock_order where status = 'CANCELLED'"))
                .isEqualTo(501L);
    }

    private JdbcTemplate createJdbcTemplate() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:account_order_cleanup_test_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        jdbcTemplate.execute("""
                create table stock_account (
                    id bigint primary key,
                    cash_balance decimal(19, 2) not null,
                    updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order (
                    id bigint primary key,
                    account_id bigint not null,
                    side varchar(10) not null,
                    status varchar(30) not null,
                    market_type varchar(30) not null default 'ORDER_BOOK',
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    filled_quantity bigint not null,
                    reserved_cash decimal(19, 2) not null,
                    created_at timestamp not null,
                    updated_at timestamp
                )
                """);
        return jdbcTemplate;
    }

    private AccountOrderCleanupService createCleanupService(
            StockHoldingRepository stockHoldingRepository,
            JdbcTemplate jdbcTemplate
    ) {
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentMarketDateTime()).thenReturn(LocalDateTime.of(2026, 7, 1, 10, 0));
        return new AccountOrderCleanupService(
                stockHoldingRepository,
                jdbcTemplate,
                simulationClockService,
                mock(AutoParticipantFundingBudgetReleaseService.class)
        );
    }

    private void insertOrder(
            JdbcTemplate jdbcTemplate,
            long accountId,
            String side,
            String status,
            String symbol,
            long quantity,
            long filledQuantity,
            String reservedCash,
            long id
    ) {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, account_id, side, status, symbol, quantity, filled_quantity, reserved_cash, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                accountId,
                side,
                status,
                symbol,
                quantity,
                filledQuantity,
                new BigDecimal(reservedCash),
                LocalDateTime.of(2026, 7, 1, 9, 0)
        );
    }

    private void insertOrder(
            JdbcTemplate jdbcTemplate,
            long accountId,
            String marketType,
            String side,
            String status,
            String symbol,
            long quantity,
            long filledQuantity,
            String reservedCash,
            long id
    ) {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, account_id, market_type, side, status, symbol,
                    quantity, filled_quantity, reserved_cash, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                accountId,
                marketType,
                side,
                status,
                symbol,
                quantity,
                filledQuantity,
                new BigDecimal(reservedCash),
                LocalDateTime.of(2026, 7, 1, 9, 0)
        );
    }

    private StockHolding holding(Long accountId, String symbol, long quantity, long reservedQuantity, String averagePrice) {
        StockHolding holding = BeanUtils.instantiateClass(StockHolding.class);
        ReflectionTestUtils.setField(holding, "accountId", accountId);
        ReflectionTestUtils.setField(holding, "symbol", symbol);
        ReflectionTestUtils.setField(holding, "quantity", quantity);
        ReflectionTestUtils.setField(holding, "reservedQuantity", reservedQuantity);
        ReflectionTestUtils.setField(holding, "averagePrice", new BigDecimal(averagePrice));
        return holding;
    }

    private Long count(JdbcTemplate jdbcTemplate, String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private BigDecimal decimal(JdbcTemplate jdbcTemplate, String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }
}
