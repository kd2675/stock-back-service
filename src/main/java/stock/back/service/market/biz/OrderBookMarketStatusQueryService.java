package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.vo.OrderBookMarketStatusResponse;
import stock.back.service.market.vo.SymbolMarketConfigResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderBookMarketStatusQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockOrderRepository stockOrderRepository;
    private final StockExecutionMarketViewRepository stockExecutionMarketViewRepository;

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
                .map(this::toOrderBookMarketConfigResponse)
                .toList();
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        long openOrderCount = stockOrderRepository.countByMarketTypeAndStatusIn(MarketType.ORDER_BOOK, openStatuses);
        long todayExecutionCount = includeTodayExecution
                ? stockExecutionMarketViewRepository.countExecutionsFromBySource(
                        LocalDate.now().atStartOfDay(),
                        ExecutionSource.INTERNAL_ORDER_BOOK
                )
                : 0L;
        long configCount = configs.size();
        long openConfigCount = configs.stream().filter(this::isConfigOpen).count();
        long instrumentCount = stockOrderBookInstrumentRepository.countByEnabledTrue();
        return new OrderBookMarketStatusResponse(
                openConfigCount > 0,
                configCount,
                openConfigCount,
                instrumentCount,
                openOrderCount,
                todayExecutionCount,
                configs
        );
    }

    private OrderBookMarketStatusResponse getOrderBookMarketSummaryStatus(boolean includeTodayExecution) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        String todayExecutionSql = includeTodayExecution
                ? """
                         (select count(*)
                            from stock_execution e
                           where e.executed_at >= ?
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
        return includeTodayExecution
                ? jdbcTemplate.queryForObject(sql, (rs, rowNum) -> toOrderBookMarketSummaryStatus(rs), todayStart)
                : jdbcTemplate.queryForObject(sql, (rs, rowNum) -> toOrderBookMarketSummaryStatus(rs));
    }

    private OrderBookMarketStatusResponse toOrderBookMarketSummaryStatus(ResultSet rs) throws SQLException {
        long configCount = rs.getLong("config_count");
        long openConfigCount = rs.getLong("open_config_count");
        return new OrderBookMarketStatusResponse(
                configCount > 0 && openConfigCount > 0,
                configCount,
                openConfigCount,
                rs.getLong("instrument_count"),
                rs.getLong("open_order_count"),
                rs.getLong("today_execution_count"),
                List.of()
        );
    }

    private SymbolMarketConfigResponse toOrderBookMarketConfigResponse(StockOrderBookMarketConfig config) {
        return new SymbolMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                normalizeMarketSessionStatus(config.getMarketStatus())
        );
    }

    private boolean isConfigOpen(SymbolMarketConfigResponse config) {
        return config.enabled() && config.marketStatus() == MarketSessionStatus.OPEN;
    }

    private MarketSessionStatus normalizeMarketSessionStatus(MarketSessionStatus marketStatus) {
        return marketStatus == null ? MarketSessionStatus.OPEN : marketStatus;
    }
}
