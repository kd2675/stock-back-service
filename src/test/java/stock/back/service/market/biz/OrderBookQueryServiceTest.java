package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private OrderBookQueryService service;

    @BeforeEach
    void setUp() {
        service = new OrderBookQueryService(jdbcTemplate, stockOrderBookInstrumentRepository, stockOrderRepository);
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
        when(resultSet.getTimestamp("executed_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 6, 30, 9, 30).minusMinutes(rowNum)));
        return rowMapper.mapRow(resultSet, rowNum);
    }
}
