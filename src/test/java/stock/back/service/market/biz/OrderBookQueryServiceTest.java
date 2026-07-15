package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderRepository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderBookQueryServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockOrderRepository stockOrderRepository;

    @Mock
    private SimulationClockService simulationClockService;

    private OrderBookQueryService service;

    @BeforeEach
    void setUp() {
        lenient().when(simulationClockService.currentMarketDayStart()).thenReturn(SimulationDayClock.currentDayStart());
        lenient().when(simulationClockService.currentMarketDateTime()).thenReturn(SimulationDayClock.currentDayStart().plusMinutes(15));
        service = new OrderBookQueryService(jdbcTemplate, stockOrderBookInstrumentRepository, stockOrderRepository, simulationClockService);
    }

    @Test
    void getRecentOrderBookExecutions_returnsPriceChangeAgainstPreviousExecution() {
        when(stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue("ZQ001")).thenReturn(true);
        when(jdbcTemplate.query(
                any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                eq("ZQ001")
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            return List.of(
                    row(rowMapper, 1L, "ZQ001", "BUY", 10L, "72000.00", "720000.00", 0),
                    row(rowMapper, 2L, "ZQ001", "SELL", 5L, "71000.00", "355000.00", 1)
            );
        });

        var executions = service.getRecentOrderBookExecutions(" zq001 ");

        assertThat(executions).hasSize(2);
        assertThat(executions.get(0).side()).isEqualTo(OrderSide.BUY);
        assertThat(executions.get(0).priceChange()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(executions.get(1).priceChange()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(stockOrderBookInstrumentRepository).existsBySymbolAndEnabledTrue("ZQ001");
    }

    @Test
    void getOrderBookTradeSummary_readsAggregateWithJdbcClient() {
        JdbcTemplate realJdbcTemplate = createJdbcTemplate();
        OrderBookQueryService realService = new OrderBookQueryService(
                realJdbcTemplate,
                stockOrderBookInstrumentRepository,
                stockOrderRepository,
                simulationClockService
        );
        when(stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue("ZQ001")).thenReturn(true);
        insertExecution(realJdbcTemplate, 1L, "ZQ001", "BUY", 2L, "70000.00", "140000.00", 2);
        insertExecution(realJdbcTemplate, 2L, "ZQ001", "SELL", 2L, "70000.00", "140000.00", 2);
        insertExecution(realJdbcTemplate, 3L, "OTHER", "BUY", 10L, "1.00", "10.00", 0);
        insertExecution(realJdbcTemplate, 4L, "ZQ001", "BUY", 100L, "99999.00", "9999900.00", 40);

        var summary = realService.getOrderBookTradeSummary("zq001");

        assertThat(summary.symbol()).isEqualTo("ZQ001");
        assertThat(summary.todayExecutionCount()).isEqualTo(1L);
        assertThat(summary.todayVolume()).isEqualTo(2L);
        assertThat(summary.todayTurnover()).isEqualByComparingTo(new BigDecimal("140000.00"));
        assertThat(summary.vwap()).isEqualByComparingTo(new BigDecimal("70000.0000"));
        assertThat(summary.highPrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(summary.lowPrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(summary.buyVolume()).isEqualTo(2L);
        assertThat(summary.sellVolume()).isEqualTo(2L);
        assertThat(summary.executionStrength()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(summary.lastPrice()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(summary.lastExecutedAt()).isEqualTo(SimulationDayClock.currentDayStart().plusMinutes(12));
    }

    @Test
    void getOrderBook_blankSymbol_throwsBadRequest() {
        assertThatThrownBy(() -> service.getOrderBook(" "))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Symbol is required");
    }

    private Object row(
            org.springframework.jdbc.core.RowMapper<Object> rowMapper,
            Long id,
            String symbol,
            String side,
            long quantity,
            String price,
            String grossAmount,
            int rowNum
    ) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(id);
        when(resultSet.getString("symbol")).thenReturn(symbol);
        when(resultSet.getString("side")).thenReturn(side);
        when(resultSet.getLong("quantity")).thenReturn(quantity);
        when(resultSet.getBigDecimal("price")).thenReturn(new BigDecimal(price));
        when(resultSet.getBigDecimal("gross_amount")).thenReturn(new BigDecimal(grossAmount));
        when(resultSet.getTimestamp("executed_at")).thenReturn(Timestamp.valueOf(SimulationDayClock.currentDayStart().plusMinutes(30 - rowNum)));
        return rowMapper.mapRow(resultSet, rowNum);
    }

    private JdbcTemplate createJdbcTemplate() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:order_book_query_service_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        jdbcTemplate.execute("""
                create table stock_execution (
                    id bigint primary key,
                    symbol varchar(20) not null,
                    side varchar(10) not null,
                    quantity bigint not null,
                    price decimal(19, 2) not null,
                    gross_amount decimal(19, 2) not null,
                    source varchar(50) not null,
                    executed_at timestamp not null
                )
                """);
        return jdbcTemplate;
    }

    private void insertExecution(
            JdbcTemplate jdbcTemplate,
            long id,
            String symbol,
            String side,
            long quantity,
            String price,
            String grossAmount,
            long minute
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(id, symbol, side, quantity, price, gross_amount, source, executed_at)
                values (?, ?, ?, ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?)
                """,
                id,
                symbol,
                side,
                quantity,
                new BigDecimal(price),
                new BigDecimal(grossAmount),
                SimulationDayClock.currentDayStart().plusMinutes(10 + minute)
        );
    }
}
