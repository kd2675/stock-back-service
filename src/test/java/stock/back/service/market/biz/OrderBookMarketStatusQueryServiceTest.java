package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    private StockExecutionMarketViewRepository stockExecutionMarketViewRepository;

    private OrderBookMarketStatusQueryService service;

    @BeforeEach
    void setUp() {
        service = new OrderBookMarketStatusQueryService(
                jdbcTemplate,
                stockOrderBookMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderRepository,
                stockExecutionMarketViewRepository
        );
    }

    @Test
    void getOrderBookMarketStatus_withoutConfigExpansion_returnsCountsWithoutLoadingConfigs() {
        stubOrderBookMarketSummaryQuery(2L, 3L, 5L, 7L, 1L, true);

        var response = service.getOrderBookMarketStatus(false);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(2L);
        assertThat(response.openConfigCount()).isEqualTo(1L);
        assertThat(response.instrumentCount()).isEqualTo(3L);
        assertThat(response.openOrderCount()).isEqualTo(5L);
        assertThat(response.todayExecutionCount()).isEqualTo(7L);
        assertThat(response.configs()).isEmpty();
        verify(stockOrderBookMarketConfigRepository, never()).findAll();
        verify(stockOrderBookMarketConfigRepository, never()).count();
        verify(stockOrderBookInstrumentRepository, never()).countByEnabledTrue();
        verify(stockOrderBookMarketConfigRepository, never()).existsByEnabledTrueAndMarketStatus(any());
        verify(stockOrderRepository, never()).countByMarketTypeAndStatusIn(any(), any());
        verify(stockExecutionMarketViewRepository, never()).countExecutionsFromBySource(any(), any());
    }

    @Test
    void getOrderBookMarketStatus_withoutTodayExecution_skipsTodayExecutionCount() {
        stubOrderBookMarketSummaryQuery(2L, 3L, 5L, 0L, 1L, false);

        var response = service.getOrderBookMarketStatus(false, false);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(2L);
        assertThat(response.openConfigCount()).isEqualTo(1L);
        assertThat(response.instrumentCount()).isEqualTo(3L);
        assertThat(response.openOrderCount()).isEqualTo(5L);
        assertThat(response.todayExecutionCount()).isZero();
        assertThat(response.configs()).isEmpty();
        verify(stockExecutionMarketViewRepository, never()).countExecutionsFromBySource(any(), any());
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
        when(stockExecutionMarketViewRepository.countExecutionsFromBySource(any(LocalDateTime.class), eq(ExecutionSource.INTERNAL_ORDER_BOOK)))
                .thenReturn(6L);
        when(stockOrderBookInstrumentRepository.countByEnabledTrue()).thenReturn(2L);

        var response = service.getOrderBookMarketStatus(true, true);

        assertThat(response.enabled()).isTrue();
        assertThat(response.configCount()).isEqualTo(2L);
        assertThat(response.openConfigCount()).isEqualTo(1L);
        assertThat(response.instrumentCount()).isEqualTo(2L);
        assertThat(response.openOrderCount()).isEqualTo(4L);
        assertThat(response.todayExecutionCount()).isEqualTo(6L);
        assertThat(response.configs()).extracting("symbol").containsExactly("ZQ001", "ZQ002");
        assertThat(response.configs()).extracting("enabled").containsExactly(true, false);
        assertThat(response.configs()).extracting("marketStatus").containsExactly(
                MarketSessionStatus.OPEN,
                MarketSessionStatus.CLOSED
        );
        verify(jdbcTemplate, never()).queryForObject(any(String.class), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any());
    }

    private void stubOrderBookMarketSummaryQuery(
            long configCount,
            long instrumentCount,
            long openOrderCount,
            long todayExecutionCount,
            long openConfigCount,
            boolean includeTodayExecution
    ) {
        org.mockito.stubbing.Answer<Object> answer = invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("config_count")).thenReturn(configCount);
            when(resultSet.getLong("instrument_count")).thenReturn(instrumentCount);
            when(resultSet.getLong("open_order_count")).thenReturn(openOrderCount);
            when(resultSet.getLong("today_execution_count")).thenReturn(todayExecutionCount);
            when(resultSet.getLong("open_config_count")).thenReturn(openConfigCount);
            return rowMapper.mapRow(resultSet, 0);
        };
        if (includeTodayExecution) {
            when(jdbcTemplate.queryForObject(
                    any(String.class),
                    org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                    any()
            )).thenAnswer(answer);
            return;
        }
        when(jdbcTemplate.queryForObject(
                any(String.class),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()
        )).thenAnswer(answer);
    }
}
