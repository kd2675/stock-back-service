package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.vo.OrderBookMarketStatusResponse;
import stock.back.service.market.vo.SymbolMarketConfigResponse;

import java.time.LocalDate;
import java.util.List;

@Service
public class OrderBookMarketStatusQueryService {

    private final JdbcClient jdbcClient;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockOrderRepository stockOrderRepository;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;

    public OrderBookMarketStatusQueryService(
            JdbcTemplate jdbcTemplate,
            StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository,
            StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository,
            StockOrderRepository stockOrderRepository,
            SimulationClockService simulationClockService,
            SimulationMarketSessionService simulationMarketSessionService
    ) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
        this.stockOrderBookMarketConfigRepository = stockOrderBookMarketConfigRepository;
        this.stockOrderBookInstrumentRepository = stockOrderBookInstrumentRepository;
        this.stockOrderRepository = stockOrderRepository;
        this.simulationClockService = simulationClockService;
        this.simulationMarketSessionService = simulationMarketSessionService;
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
                ? countTodayInternalOrderBookExecutions()
                : 0L;
        long configCount = configs.size();
        long openConfigCount = effectiveOpenConfigCount(configs.stream().filter(OrderBookMarketStatusResponseMapper::isOpen).count());
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
        LocalDate todayDate = simulationClockService.currentMarketDateTime().toLocalDate();
        String todayExecutionSql = includeTodayExecution
                ? """
                         (select coalesce(sum(summary.execution_count), 0) / 2
                            from stock_execution_account_day_summary summary
                           where summary.simulation_trade_date = :todayDate) as today_execution_count
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
            OrderBookMarketStatusResponse response = jdbcClient.sql(sql)
                    .param("todayDate", todayDate)
                    .query((rs, rowNum) -> OrderBookMarketStatusResponseMapper.toSummaryStatus(rs))
                    .single();
            return withEffectiveSession(response);
        }
        OrderBookMarketStatusResponse response = jdbcClient.sql(sql)
                .query((rs, rowNum) -> OrderBookMarketStatusResponseMapper.toSummaryStatus(rs))
                .single();
        return withEffectiveSession(response);
    }

    /**
     * Internal order-book matching persists one BUY and one SELL account delta per trade. Reading the
     * asynchronously flushed day summary keeps this status endpoint off the append-only execution ledger.
     * The value is intentionally eventually consistent (normally within the batch flush interval).
     */
    private long countTodayInternalOrderBookExecutions() {
        Long accountSideExecutionCount = jdbcClient.sql(
                        """
                        select coalesce(sum(summary.execution_count), 0)
                          from stock_execution_account_day_summary summary
                         where summary.simulation_trade_date = ?
                        """
                )
                .param(simulationClockService.currentMarketDateTime().toLocalDate())
                .query(Long.class)
                .single();
        return accountSideExecutionCount == null ? 0L : accountSideExecutionCount / 2L;
    }

    private long effectiveOpenConfigCount(long openConfigCount) {
        return simulationMarketSessionService.isRegularSession() ? openConfigCount : 0L;
    }

    private OrderBookMarketStatusResponse withEffectiveSession(OrderBookMarketStatusResponse response) {
        return OrderBookMarketStatusResponseMapper.toStatus(
                response.configCount(),
                effectiveOpenConfigCount(response.openConfigCount()),
                response.instrumentCount(),
                response.openOrderCount(),
                response.todayExecutionCount(),
                response.configs()
        );
    }
}
