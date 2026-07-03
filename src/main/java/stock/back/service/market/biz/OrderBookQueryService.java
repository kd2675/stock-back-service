package stock.back.service.market.biz;

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.vo.OrderBookLevelResponse;
import stock.back.service.market.vo.OrderBookRecentExecutionResponse;
import stock.back.service.market.vo.OrderBookResponse;
import stock.back.service.market.vo.OrderBookTradeSummaryResponse;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderBookQueryService {

    private final JdbcClient jdbcClient;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockOrderRepository stockOrderRepository;
    private final SimulationClockService simulationClockService;

    public OrderBookQueryService(
            JdbcTemplate jdbcTemplate,
            StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository,
            StockOrderRepository stockOrderRepository,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.stockOrderBookInstrumentRepository = stockOrderBookInstrumentRepository;
        this.stockOrderRepository = stockOrderRepository;
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true)
    public OrderBookResponse getOrderBook(String symbol) {
        String normalizedSymbol = requireEnabledOrderBookSymbol(symbol);
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        var page = PageRequest.of(0, 10);
        List<OrderBookLevelResponse> bids = stockOrderRepository
                .findBidLevels(normalizedSymbol, MarketType.ORDER_BOOK, OrderSide.BUY, OrderType.LIMIT, openStatuses, page)
                .stream()
                .map(OrderBookResponseMapper::toLevel)
                .toList();
        List<OrderBookLevelResponse> asks = stockOrderRepository
                .findAskLevels(normalizedSymbol, MarketType.ORDER_BOOK, OrderSide.SELL, OrderType.LIMIT, openStatuses, page)
                .stream()
                .map(OrderBookResponseMapper::toLevel)
                .toList();
        return new OrderBookResponse(normalizedSymbol, bids, asks);
    }

    @Transactional(readOnly = true)
    public OrderBookTradeSummaryResponse getOrderBookTradeSummary(String symbol) {
        String normalizedSymbol = requireEnabledOrderBookSymbol(symbol);
        LocalDateTime todayStart = simulationClockService.currentMarketDayStart();
        LocalDateTime todayEnd = simulationClockService.currentMarketDateTime();
        String sql = """
                select
                  coalesce(count(*), 0) as today_execution_count,
                  coalesce(sum(quantity), 0) as today_volume,
                  coalesce(sum(gross_amount), 0) as today_turnover,
                  coalesce(sum(case when side = 'BUY' then quantity else 0 end), 0) as buy_volume,
                  coalesce(sum(case when side = 'SELL' then quantity else 0 end), 0) as sell_volume,
                  coalesce(sum(case when side = 'BUY' then gross_amount else 0 end), 0) as buy_turnover,
                  coalesce(sum(case when side = 'SELL' then gross_amount else 0 end), 0) as sell_turnover,
                  min(price) as low_price,
                  max(price) as high_price,
                  (select e.price
                     from stock_execution e
                    where e.symbol = ?
                      and e.source = 'INTERNAL_ORDER_BOOK'
                      and e.executed_at <= ?
                    order by e.executed_at desc, e.id desc
                    limit 1) as last_price,
                  (select e.executed_at
                     from stock_execution e
                    where e.symbol = ?
                      and e.source = 'INTERNAL_ORDER_BOOK'
                      and e.executed_at <= ?
                    order by e.executed_at desc, e.id desc
                    limit 1) as last_executed_at
                from stock_execution
                where symbol = ?
                  and source = 'INTERNAL_ORDER_BOOK'
                  and executed_at >= ?
                  and executed_at <= ?
                """;
        return jdbcClient.sql(sql)
                .params(normalizedSymbol, todayEnd, normalizedSymbol, todayEnd, normalizedSymbol, todayStart, todayEnd)
                .query((rs, rowNum) -> OrderBookResponseMapper.toTradeSummary(normalizedSymbol, rs))
                .single();
    }

    @Transactional(readOnly = true)
    public List<OrderBookRecentExecutionResponse> getRecentOrderBookExecutions(String symbol) {
        String normalizedSymbol = requireEnabledOrderBookSymbol(symbol);
        String sql = """
                select id,
                       symbol,
                       side,
                       quantity,
                       price,
                       gross_amount,
                       executed_at
                  from stock_execution
                 where symbol = ?
                   and source = 'INTERNAL_ORDER_BOOK'
                 order by executed_at desc, id desc
                 limit 30
                """;
        List<OrderBookResponseMapper.RecentExecutionRow> rows = jdbcClient.sql(sql)
                .param(normalizedSymbol)
                .query((rs, rowNum) -> OrderBookResponseMapper.toRecentExecutionRow(rs))
                .list();
        return OrderBookResponseMapper.toRecentExecutions(rows);
    }

    private String requireEnabledOrderBookSymbol(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue(normalizedSymbol)) {
            throw StockException.notFound("Unknown stock symbol: " + normalizedSymbol);
        }
        return normalizedSymbol;
    }

}
