package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.common.exception.StockException;
import web.common.core.simulation.SimulationClockSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstrumentMarketReportQueryServiceTest {

    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 10, 3, 0, 0);
    private static final LocalDateTime NOW = DAY_START.plusDays(1).plusHours(1);

    private JdbcTemplate jdbcTemplate;
    private InstrumentMarketReportQueryService service;
    private InstrumentReportService instrumentReportService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:instrument_market_report_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        createSchema();
        jdbcTemplate.update("delete from stock_order_book_daily_snapshot");
        jdbcTemplate.update("delete from stock_post_close_cycle");
        jdbcTemplate.update("delete from stock_market_close_run");
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_order_book_market_config");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_order_book_instrument");
        instrumentReportService = mock(InstrumentReportService.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        InstrumentMarketReportAnalyticsQueryService analyticsQueryService = mock(InstrumentMarketReportAnalyticsQueryService.class);
        when(simulationClockService.currentSnapshot()).thenReturn(new SimulationClockSnapshot(
                DAY_START.toLocalDate(),
                NOW,
                DAY_START,
                NOW,
                DAY_START,
                7_200,
                false,
                false,
                0L,
                null,
                null
        ));
        service = new InstrumentMarketReportQueryService(
                jdbcTemplate,
                instrumentReportService,
                simulationClockService,
                analyticsQueryService
        );
    }

    @Test
    void getInstrumentMarketReport_usesLatestFinalizedGlobalReportAndExcludesPartialOrLiveState() {
        insertInstrument();
        insertCompletedDailySnapshot();
        jdbcTemplate.update("update stock_order_book_instrument set enabled = false where symbol = 'DEMO002'");
        insertExecutionPair(1L, 2L, 3_000L, "6000.00", DAY_START.plusHours(9));
        insertExecutionPair(3L, 4L, 1_000L, "6050.00", DAY_START.plusHours(10));
        insertExecutionPair(5L, 6L, 9_000L, "6100.00", NOW.minusMinutes(10));
        var report = service.getInstrumentMarketReport(" demo002 ");

        assertThat(report).extracting(
                value -> value.symbol(),
                value -> value.closePrice(),
                value -> value.marketCapitalization(),
                value -> value.tradableMarketCapitalization(),
                value -> value.changeRate(),
                value -> value.tradableShareRate(),
                value -> value.lowerLimitPrice(),
                value -> value.upperLimitPrice(),
                value -> value.reportDate(),
                value -> value.daily().tradeCount(),
                value -> value.daily().volume(),
                value -> value.daily().turnover(),
                value -> value.daily().turnoverRate(),
                value -> value.daily().vwap(),
                value -> value.daily().openPrice(),
                value -> value.daily().lastPrice(),
                value -> value.closePriceProvider()
        ).containsExactly(
                "DEMO002",
                new BigDecimal("6050.00"),
                new BigDecimal("60500000000.00"),
                new BigDecimal("48400000000.00"),
                new BigDecimal("0.8333"),
                new BigDecimal("80.0000"),
                new BigDecimal("4200.00"),
                new BigDecimal("7800.00"),
                LocalDate.of(2026, 10, 3),
                2L,
                4_000L,
                new BigDecimal("24050000.00"),
                new BigDecimal("0.0500"),
                new BigDecimal("6012.5000"),
                new BigDecimal("6000.00"),
                new BigDecimal("6050.00"),
                "internal-order-book"
        );
        verify(instrumentReportService).getLatestInstrumentReportAt(
                "DEMO002",
                DAY_START.plusHours(18)
        );
    }

    @Test
    void getInstrumentMarketReport_unknownSymbol_throwsNotFound() {
        assertThatThrownBy(() -> service.getInstrumentMarketReport("UNKNOWN"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown stock symbol");
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table if not exists stock_market_close_run (
                    id bigint primary key,
                    symbol varchar(20),
                    business_date date not null,
                    status varchar(20) not null,
                    completed_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists stock_post_close_cycle (
                    id bigint primary key,
                    close_run_id bigint,
                    scope_type varchar(20) not null,
                    scope_key varchar(120) not null,
                    phase varchar(60) not null
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists stock_order_book_daily_snapshot (
                    id bigint primary key,
                    close_run_id bigint not null,
                    symbol varchar(20) not null,
                    simulation_trade_date date not null,
                    name varchar(120) not null,
                    market varchar(20) not null,
                    initial_price decimal(19, 2) not null,
                    close_price decimal(19, 2) not null,
                    previous_close decimal(19, 2) not null,
                    issued_shares bigint not null,
                    tradable_shares bigint not null,
                    price_limit_rate decimal(5, 2) not null,
                    price_time timestamp,
                    price_provider varchar(40),
                    execution_count bigint not null,
                    buy_quantity bigint not null,
                    turnover_amount decimal(19, 2) not null,
                    open_price decimal(19, 2) not null,
                    high_price decimal(19, 2) not null,
                    low_price decimal(19, 2) not null,
                    last_execution_price decimal(19, 2) not null,
                    last_executed_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists stock_order_book_instrument (
                    symbol varchar(20) primary key,
                    name varchar(120) not null,
                    market varchar(20) not null,
                    initial_price decimal(19, 2) not null,
                    issued_shares bigint not null,
                    tradable_shares bigint not null,
                    price_limit_rate decimal(5, 2) not null,
                    enabled boolean not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists stock_price (
                    symbol varchar(20) primary key,
                    current_price decimal(19, 2) not null,
                    previous_close decimal(19, 2) not null,
                    price_time timestamp not null,
                    provider varchar(40) not null
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists stock_order_book_market_config (
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists stock_execution (
                    id bigint primary key,
                    symbol varchar(20) not null,
                    side varchar(10) not null,
                    quantity bigint not null,
                    price decimal(19, 2) not null,
                    gross_amount decimal(19, 2) not null,
                    source varchar(30) not null,
                    executed_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists stock_order (
                    id bigint primary key,
                    symbol varchar(20) not null,
                    market_type varchar(30) not null,
                    side varchar(10) not null,
                    order_type varchar(10) not null,
                    status varchar(20) not null,
                    limit_price decimal(19, 2),
                    quantity bigint not null,
                    filled_quantity bigint not null
                )
                """);
    }

    private void insertInstrument() {
        jdbcTemplate.update("""
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares, tradable_shares,
                    price_limit_rate, enabled, updated_at
                ) values ('DEMO002', '주식2', 'KOSPI', 5000.00, 10000000, 8000000, 30.00, true, ?)
                """, DAY_START);
        jdbcTemplate.update("""
                insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                values ('DEMO002', 6200.00, 6100.00, ?, 'internal-order-book')
                """, NOW);
        jdbcTemplate.update("""
                insert into stock_order_book_market_config(symbol, enabled, market_status)
                values ('DEMO002', true, 'OPEN')
                """);
    }

    private void insertCompletedDailySnapshot() {
        jdbcTemplate.update("""
                insert into stock_market_close_run(id, symbol, business_date, status, completed_at)
                values (1, null, ?, 'COMPLETED', ?)
                """, DAY_START.toLocalDate(), DAY_START.plusHours(18));
        jdbcTemplate.update("""
                insert into stock_post_close_cycle(id, close_run_id, scope_type, scope_key, phase)
                values (1, 1, 'FULL_MARKET', 'ALL', 'REPORTS_AGGREGATED')
                """);
        jdbcTemplate.update("""
                insert into stock_order_book_daily_snapshot(
                    id, close_run_id, symbol, simulation_trade_date, name, market, initial_price,
                    close_price, previous_close, issued_shares, tradable_shares, price_limit_rate,
                    price_time, price_provider, execution_count, buy_quantity, turnover_amount,
                    open_price, high_price, low_price, last_execution_price, last_executed_at
                ) values (1, 1, 'DEMO002', ?, '주식2', 'KOSPI', 5000.00,
                    6050.00, 6000.00, 10000000, 8000000, 30.00,
                    ?, 'internal-order-book', 2, 4000, 24050000.00,
                    6000.00, 6050.00, 6000.00, 6050.00, ?)
                """, DAY_START.toLocalDate(), DAY_START.plusHours(10), DAY_START.plusHours(10));
        jdbcTemplate.update("""
                insert into stock_market_close_run(id, symbol, business_date, status, completed_at)
                values (3, null, ?, 'COMPLETED', ?)
                """, NOW.toLocalDate(), NOW.minusMinutes(30));
        jdbcTemplate.update("""
                insert into stock_post_close_cycle(id, close_run_id, scope_type, scope_key, phase)
                values (3, 3, 'FULL_MARKET', 'ALL', 'CORPORATE_CASH_APPLIED')
                """);
        jdbcTemplate.update("""
                insert into stock_order_book_daily_snapshot(
                    id, close_run_id, symbol, simulation_trade_date, name, market, initial_price,
                    close_price, previous_close, issued_shares, tradable_shares, price_limit_rate,
                    price_time, price_provider, execution_count, buy_quantity, turnover_amount,
                    open_price, high_price, low_price, last_execution_price, last_executed_at
                ) values (3, 3, 'DEMO002', ?, '주식2', 'KOSPI', 5000.00,
                    9999.00, 6050.00, 10000000, 8000000, 30.00,
                    ?, 'internal-order-book', 99, 99000, 989901000.00,
                    9999.00, 9999.00, 9999.00, 9999.00, ?)
                """, NOW.toLocalDate(), NOW.minusMinutes(30), NOW.minusMinutes(30));
        jdbcTemplate.update("""
                insert into stock_market_close_run(id, symbol, business_date, status, completed_at)
                values (2, 'DEMO002', ?, 'COMPLETED', ?)
                """, NOW.toLocalDate(), NOW.minusMinutes(20));
        jdbcTemplate.update("""
                insert into stock_order_book_daily_snapshot(
                    id, close_run_id, symbol, simulation_trade_date, name, market, initial_price,
                    close_price, previous_close, issued_shares, tradable_shares, price_limit_rate,
                    price_time, price_provider, execution_count, buy_quantity, turnover_amount,
                    open_price, high_price, low_price, last_execution_price, last_executed_at
                ) values (2, 2, 'DEMO002', ?, '주식2', 'KOSPI', 5000.00,
                    6200.00, 6050.00, 10000000, 8000000, 30.00,
                    ?, 'internal-order-book', 1, 9000, 54900000.00,
                    6100.00, 6100.00, 6100.00, 6100.00, ?)
                """, NOW.toLocalDate(), NOW, NOW);
    }

    private void insertExecutionPair(long buyId, long sellId, long quantity, String price, LocalDateTime executedAt) {
        BigDecimal executionPrice = new BigDecimal(price);
        BigDecimal grossAmount = executionPrice.multiply(BigDecimal.valueOf(quantity));
        jdbcTemplate.update("""
                insert into stock_execution(id, symbol, side, quantity, price, gross_amount, source, executed_at)
                values (?, 'DEMO002', 'BUY', ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?)
                """, buyId, quantity, executionPrice, grossAmount, executedAt);
        jdbcTemplate.update("""
                insert into stock_execution(id, symbol, side, quantity, price, gross_amount, source, executed_at)
                values (?, 'DEMO002', 'SELL', ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?)
                """, sellId, quantity, executionPrice, grossAmount, executedAt);
    }

}
