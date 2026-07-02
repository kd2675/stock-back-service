package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.vo.OrderBookMarketStatusResponse;
import stock.back.service.market.vo.SymbolMarketConfigResponse;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderBookMarketStatusQueryService {

    private final JdbcClient jdbcClient;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockOrderRepository stockOrderRepository;
    private final StockExecutionMarketViewRepository stockExecutionMarketViewRepository;
    private final SimulationClockService simulationClockService;

    public OrderBookMarketStatusQueryService(
            JdbcTemplate jdbcTemplate,
            StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository,
            StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository,
            StockOrderRepository stockOrderRepository,
            StockExecutionMarketViewRepository stockExecutionMarketViewRepository,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
        this.stockOrderBookMarketConfigRepository = stockOrderBookMarketConfigRepository;
        this.stockOrderBookInstrumentRepository = stockOrderBookInstrumentRepository;
        this.stockOrderRepository = stockOrderRepository;
        this.stockExecutionMarketViewRepository = stockExecutionMarketViewRepository;
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true)
    public OrderBookMarketStatusResponse getOrderBookMarketStatus() {
        return getOrderBookMarketStatus(true, true);
    }

    @Transactional(readOnly = true)
    public OrderBookMarketStatusResponse getOrderBookMarketStatus(boolean includeConfigs) {
        return getOrderBookMarketStatus(includeConfigs, true);
    }

    @Transactional(readOnly = true)
    public OrderBookMarketStatusResponse getOrderBookMarketStatus(boolean includeConfigs, boolean includeTodayExecution) {
        if (!includeConfigs) {
            return getOrderBookMarketSummaryStatus(includeTodayExecution);
        }
        List<SymbolMarketConfigResponse> configs = stockOrderBookMarketConfigRepository.findAll().stream()
                .sorted((left, right) -> left.getSymbol().compareTo(right.getSymbol()))
                .map(OrderBookMarketStatusResponseMapper::toMarketConfig)
                .toList();
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        long openOrderCount = stockOrderRepository.countByMarketTypeAndStatusIn(MarketType.ORDER_BOOK, openStatuses);
        long todayExecutionCount = includeTodayExecution
                ? stockExecutionMarketViewRepository.countExecutionsFromBySource(
                        simulationClockService.currentMarketDayStart(),
                        ExecutionSource.INTERNAL_ORDER_BOOK
                )
                : 0L;
        long configCount = configs.size();
        long openConfigCount = configs.stream().filter(OrderBookMarketStatusResponseMapper::isOpen).count();
        long instrumentCount = stockOrderBookInstrumentRepository.countByEnabledTrue();
        return OrderBookMarketStatusResponseMapper.toStatus(
                configCount,
                openConfigCount,
                instrumentCount,
                openOrderCount,
                todayExecutionCount,
                configs
        );
    }

    private OrderBookMarketStatusResponse getOrderBookMarketSummaryStatus(boolean includeTodayExecution) {
        LocalDateTime todayStart = simulationClockService.currentMarketDayStart();
        String todayExecutionSql = includeTodayExecution
                ? """
                         (select count(*)
                            from stock_execution e
                           where e.executed_at >= :todayStart
                             and e.source = 'INTERNAL_ORDER_BOOK') as today_execution_count
                        """
                : "0 as today_execution_count";
        String sql = """
                select (select count(*) from stock_order_book_market_config) as config_count,
                       (select count(*)
                          from stock_order_book_instrument i
                         where i.enabled = true) as instrument_count,
                       (select count(*)
                          from stock_order o
                         where o.market_type = 'ORDER_BOOK'
                           and o.status in ('PENDING', 'PARTIALLY_FILLED')) as open_order_count,
                       %s,
                       (select count(*)
                          from stock_order_book_market_config m
                         where m.enabled = true
                           and m.market_status = 'OPEN') as open_config_count
                """.formatted(todayExecutionSql);
        if (includeTodayExecution) {
            return jdbcClient.sql(sql)
                    .param("todayStart", todayStart)
                    .query((rs, rowNum) -> OrderBookMarketStatusResponseMapper.toSummaryStatus(rs))
                    .single();
        }
        return jdbcClient.sql(sql)
                .query((rs, rowNum) -> OrderBookMarketStatusResponseMapper.toSummaryStatus(rs))
                .single();
    }
}
