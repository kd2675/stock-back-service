package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderBookMarketStatusQueryServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockOrderRepository stockOrderRepository;

    @Mock
    private SimulationClockService simulationClockService;

    @Mock
    private SimulationMarketSessionService simulationMarketSessionService;

    private OrderBookMarketStatusQueryService service;

    @BeforeEach
    void setUp() {
        lenient().when(simulationClockService.currentMarketDayStart()).thenReturn(SimulationDayClock.currentDayStart());
        lenient().when(simulationClockService.currentMarketDateTime()).thenReturn(SimulationDayClock.currentDayStart().plusMinutes(25));
        lenient().when(simulationMarketSessionService.isRegularSession()).thenReturn(true);
        service = new OrderBookMarketStatusQueryService(
                jdbcTemplate,
                stockOrderBookMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderRepository,
                simulationClockService,
                simulationMarketSessionService
        );
    }

    @Test
    void getOrderBookMarketStatus_withoutConfigExpansion_returnsCountsWithoutLoadingConfigs() {
        service = new OrderBookMarketStatusQueryService(
                createSummaryJdbcTemplate("order_book_market_status_summary_with_today_test"),
                stockOrderBookMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderRepository,
                simulationClockService,
                simulationMarketSessionService
        );
        seedSummaryRows(true);

        var response = service.getOrderBookMarketStatus(false);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(2L);
        assertThat(response.openConfigCount()).isEqualTo(1L);
        assertThat(response.instrumentCount()).isEqualTo(3L);
        assertThat(response.openOrderCount()).isEqualTo(3L);
        assertThat(response.todayExecutionCount()).isEqualTo(1L);
        assertThat(response.configs()).isEmpty();
        verify(stockOrderBookMarketConfigRepository, never()).findAll();
        verify(stockOrderBookMarketConfigRepository, never()).count();
        verify(stockOrderBookInstrumentRepository, never()).countByEnabledTrue();
        verify(stockOrderBookMarketConfigRepository, never()).existsByEnabledTrueAndMarketStatus(any());
        verify(stockOrderRepository, never()).countByMarketTypeAndStatusIn(any(), any());
    }

    @Test
    void getOrderBookMarketStatus_withoutTodayExecution_skipsTodayExecutionCount() {
        service = new OrderBookMarketStatusQueryService(
                createSummaryJdbcTemplate("order_book_market_status_summary_without_today_test"),
                stockOrderBookMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderRepository,
                simulationClockService,
                simulationMarketSessionService
        );
        seedSummaryRows(true);

        var response = service.getOrderBookMarketStatus(false, false);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(2L);
        assertThat(response.openConfigCount()).isEqualTo(1L);
        assertThat(response.instrumentCount()).isEqualTo(3L);
        assertThat(response.openOrderCount()).isEqualTo(3L);
        assertThat(response.todayExecutionCount()).isZero();
        assertThat(response.configs()).isEmpty();
        verify(stockOrderRepository, never()).countByMarketTypeAndStatusIn(any(), any());
    }

    @Test
    void getOrderBookMarketStatus_withConfigExpansion_sortsConfigsAndCountsOpenConfigs() {
        StockOrderBookMarketConfig closedConfig = StockOrderBookMarketConfig.enabled("ZQ002");
        closedConfig.updateStatus(false, MarketSessionStatus.CLOSED);
        StockOrderBookMarketConfig openConfig = StockOrderBookMarketConfig.enabled("ZQ001");
        when(stockOrderBookMarketConfigRepository.findAll()).thenReturn(List.of(closedConfig, openConfig));
        when(stockOrderRepository.countByMarketTypeAndStatusIn(
                eq(MarketType.ORDER_BOOK),
                eq(List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED))
        )).thenReturn(4L);
        when(stockOrderBookInstrumentRepository.countByEnabledTrue()).thenReturn(2L);

        service = new OrderBookMarketStatusQueryService(
                createSummaryJdbcTemplate("order_book_market_status_with_configs_test"),
                stockOrderBookMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderRepository,
                simulationClockService,
                simulationMarketSessionService
        );
        seedExecutionSummary(6L);

        var response = service.getOrderBookMarketStatus(true, true);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(2L);
        assertThat(response.openConfigCount()).isEqualTo(1L);
        assertThat(response.instrumentCount()).isEqualTo(2L);
        assertThat(response.openOrderCount()).isEqualTo(4L);
        assertThat(response.todayExecutionCount()).isEqualTo(3L);
        assertThat(response.configs()).extracting("symbol").containsExactly("ZQ001", "ZQ002");
        assertThat(response.configs()).extracting("enabled").containsExactly(true, false);
        assertThat(response.configs()).extracting("marketStatus").containsExactly(
                MarketSessionStatus.OPEN,
                MarketSessionStatus.CLOSED
        );
    }

    @Test
    void getOrderBookMarketStatus_outsideRegularSession_reportsClosedEffectiveStatus() {
        StockOrderBookMarketConfig openConfig = StockOrderBookMarketConfig.enabled("ZQ001");
        when(stockOrderBookMarketConfigRepository.findAll()).thenReturn(List.of(openConfig));
        when(stockOrderRepository.countByMarketTypeAndStatusIn(
                eq(MarketType.ORDER_BOOK),
                eq(List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED))
        )).thenReturn(4L);
        when(stockOrderBookInstrumentRepository.countByEnabledTrue()).thenReturn(1L);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        service = new OrderBookMarketStatusQueryService(
                createSummaryJdbcTemplate("order_book_market_status_closed_session_test"),
                stockOrderBookMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderRepository,
                simulationClockService,
                simulationMarketSessionService
        );
        seedExecutionSummary(6L);

        var response = service.getOrderBookMarketStatus(true, true);

        assertThat(response.enabled()).isFalse();
        assertThat(response.openConfigCount()).isZero();
        assertThat(response.configs()).extracting("marketStatus").containsExactly(MarketSessionStatus.OPEN);
    }

    private JdbcTemplate createSummaryJdbcTemplate(String databaseName) {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        jdbcTemplate.execute("""
                create table stock_order_book_market_config (
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_instrument (
                    symbol varchar(20) primary key,
                    enabled boolean not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order (
                    id bigint primary key,
                    market_type varchar(30) not null,
                    status varchar(30) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_execution_account_day_summary (
                    simulation_trade_date date not null,
                    account_id bigint not null,
                    execution_count bigint not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        return jdbcTemplate;
    }

    private void seedSummaryRows(boolean includeTodayExecutionRows) {
        jdbcTemplate.update(
                "insert into stock_order_book_market_config(symbol, enabled, market_status) values ('ZQ001', true, 'OPEN')"
        );
        jdbcTemplate.update(
                "insert into stock_order_book_market_config(symbol, enabled, market_status) values ('ZQ002', true, 'CLOSED')"
        );
        jdbcTemplate.update(
                "insert into stock_order_book_instrument(symbol, enabled) values ('ZQ001', true)"
        );
        jdbcTemplate.update(
                "insert into stock_order_book_instrument(symbol, enabled) values ('ZQ002', true)"
        );
        jdbcTemplate.update(
                "insert into stock_order_book_instrument(symbol, enabled) values ('ZQ003', true)"
        );
        jdbcTemplate.update(
                "insert into stock_order(id, market_type, status) values (1, 'ORDER_BOOK', 'PENDING')"
        );
        jdbcTemplate.update(
                "insert into stock_order(id, market_type, status) values (2, 'ORDER_BOOK', 'PARTIALLY_FILLED')"
        );
        jdbcTemplate.update(
                "insert into stock_order(id, market_type, status) values (3, 'ORDER_BOOK', 'FILLED')"
        );
        jdbcTemplate.update(
                "insert into stock_order(id, market_type, status) values (4, 'VIRTUAL_PRICE', 'PENDING')"
        );
        jdbcTemplate.update(
                "insert into stock_order(id, market_type, status) values (5, 'ORDER_BOOK', 'PENDING')"
        );
        if (!includeTodayExecutionRows) {
            return;
        }
        seedExecutionSummary(2L);
    }

    private void seedExecutionSummary(long executionCount) {
        jdbcTemplate.update(
                "insert into stock_execution_account_day_summary(simulation_trade_date, account_id, execution_count) values (?, ?, ?)",
                SimulationDayClock.currentDayStart().toLocalDate(),
                100L + executionCount,
                executionCount
        );
    }
}
