package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AdminFundFlowScope;
import stock.back.service.market.vo.AdminSymbolFlowDailyCumulativeResponse;
import stock.back.service.market.vo.AdminSymbolFlowListResponse;
import stock.back.service.market.vo.AdminSymbolFlowResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

@Service
public class AdminSymbolFlowQueryService {

    private static final String SYMBOL_FLOW_SELECT_COLUMNS = """
                       i.symbol,
                       i.name,
                       i.enabled,
                       coalesce(m.market_status, 'CLOSED') as market_status,
                       i.issued_shares,
                       i.tradable_shares,
                       %s as current_price,
                       %s as previous_close,
                       coalesce(e.execution_count, 0) as execution_count,
                       coalesce(e.execution_quantity, 0) as execution_quantity,
                       coalesce(e.turnover_amount, 0) as turnover_amount,
                       coalesce(e.buy_quantity, 0) as buy_quantity,
                       coalesce(e.sell_quantity, 0) as sell_quantity,
                       coalesce(e.buy_net_amount, 0) as buy_net_amount,
                       coalesce(e.sell_net_amount, 0) as sell_net_amount,
                       coalesce(o.open_order_count, 0) as open_order_count,
                       coalesce(o.open_buy_order_count, 0) as open_buy_order_count,
                       coalesce(o.open_sell_order_count, 0) as open_sell_order_count,
                       coalesce(o.reserved_buy_cash, 0) as reserved_buy_cash,
                       coalesce(h.holder_count, 0) as holder_count,
                       coalesce(h.holding_quantity, 0) as holding_quantity,
                       coalesce(c.pending_corporate_action_count, 0) as pending_corporate_action_count,
                       e.last_executed_at
            """;

    private static final String SYMBOL_FLOW_ORDER_BY_SQL = """
                 order by coalesce(e.turnover_amount, 0) desc,
                          coalesce(e.execution_count, 0) desc,
                          i.symbol asc
            """;

    private static final String DAILY_SNAPSHOT_ORDER_BY_SQL = """
                 order by s.turnover_amount desc,
                          s.execution_count desc,
                          s.symbol asc
            """;

    private final JdbcClient jdbcClient;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final SimulationClockService simulationClockService;

    public AdminSymbolFlowQueryService(
            JdbcTemplate jdbcTemplate,
            StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.stockOrderBookInstrumentRepository = stockOrderBookInstrumentRepository;
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true)
    public AdminSymbolFlowListResponse getAdminSymbolFlows(int symbolFlowLimit) {
        return getAdminSymbolFlows(symbolFlowLimit, AdminFundFlowScope.RECENT_SIMULATION_DAY);
    }

    @Transactional(readOnly = true)
    public AdminSymbolFlowListResponse getAdminSymbolFlows(int symbolFlowLimit, AdminFundFlowScope scope) {
        return getAdminSymbolFlows(symbolFlowLimit, scope, false, 0);
    }

    @Transactional(readOnly = true)
    public AdminSymbolFlowListResponse getAdminSymbolFlows(
            int symbolFlowLimit,
            AdminFundFlowScope scope,
            boolean includeDailyCumulative,
            int dailyCumulativeDays
    ) {
        return getAdminSymbolFlows(
                symbolFlowLimit,
                scope,
                includeDailyCumulative,
                dailyCumulativeDays,
                0
        );
    }

    @Transactional(readOnly = true)
    public AdminSymbolFlowListResponse getAdminSymbolFlows(
            int symbolFlowLimit,
            AdminFundFlowScope scope,
            boolean includeDailyCumulative,
            int dailyCumulativeDays,
            int dailyCumulativeDayOffset
    ) {
        int normalizedSymbolFlowLimit = Math.clamp(symbolFlowLimit, 0, 500);
        int normalizedDailyCumulativeDays = Math.clamp(dailyCumulativeDays, 1, 14);
        int normalizedDailyCumulativeDayOffset = Math.clamp(dailyCumulativeDayOffset, 0, 3650);
        List<AdminSymbolFlowResponse> symbolFlows = loadAdminSymbolFlows(
                normalizedSymbolFlowLimit,
                symbolFlowExecutionWindow(normalizeScope(scope))
        );
        long totalCount = normalizedSymbolFlowLimit > 0
                ? countSymbols()
                : symbolFlows.size();
        List<AdminSymbolFlowDailyCumulativeResponse> dailyCumulativeFlows = includeDailyCumulative
                ? loadDailyCumulativeSymbolFlows(
                        normalizedSymbolFlowLimit,
                        normalizedDailyCumulativeDays,
                        normalizedDailyCumulativeDayOffset
                )
                : List.of();
        return new AdminSymbolFlowListResponse(totalCount, symbolFlows, dailyCumulativeFlows);
    }

    @Transactional(readOnly = true)
    public long countSymbols() {
        return stockOrderBookInstrumentRepository.count();
    }

    private List<AdminSymbolFlowResponse> loadAdminSymbolFlows(int limit, SymbolFlowExecutionWindow executionWindow) {
        return loadAdminSymbolFlows(limit, executionWindow, null);
    }

    private List<AdminSymbolFlowResponse> loadAdminSymbolFlows(
            int limit,
            SymbolFlowExecutionWindow executionWindow,
            LocalDate priceSnapshotTradeDate
    ) {
        return loadAdminSymbolFlows(limit, executionWindow, priceSnapshotTradeDate, true);
    }

    private List<AdminSymbolFlowResponse> loadAdminSymbolFlows(
            int limit,
            SymbolFlowExecutionWindow executionWindow,
            LocalDate priceSnapshotTradeDate,
            boolean fallbackToInitialPrice
    ) {
        if (limit > 0) {
            return loadLimitedAdminSymbolFlows(limit, executionWindow, priceSnapshotTradeDate, fallbackToInitialPrice);
        }
        return loadAllAdminSymbolFlows(executionWindow, priceSnapshotTradeDate, fallbackToInitialPrice);
    }

    private List<AdminSymbolFlowResponse> loadLimitedAdminSymbolFlows(
            int limit,
            SymbolFlowExecutionWindow executionWindow,
            LocalDate priceSnapshotTradeDate,
            boolean fallbackToInitialPrice
    ) {
        String sql = """
                with
                """ + priceSourceCteSql(priceSnapshotTradeDate) + """
                  execution_flow as (
                       select symbol,
                              count(*) as execution_count,
                              sum(quantity) as execution_quantity,
                              sum(gross_amount) as turnover_amount,
                              sum(case when side = 'BUY' then quantity else 0 end) as buy_quantity,
                              sum(case when side = 'SELL' then quantity else 0 end) as sell_quantity,
                              sum(case when side = 'BUY' then net_amount else 0 end) as buy_net_amount,
                              sum(case when side = 'SELL' then net_amount else 0 end) as sell_net_amount,
                              max(executed_at) as last_executed_at
                         from stock_execution
                        where source = 'INTERNAL_ORDER_BOOK'
                """ + executionWindow.predicateSql() + """
                        group by symbol
                  ),
                  selected_symbols as (
                       select i.symbol
                         from stock_order_book_instrument i
                         left join execution_flow e on e.symbol = i.symbol
                        order by coalesce(e.turnover_amount, 0) desc,
                                 coalesce(e.execution_count, 0) desc,
                                 i.symbol asc
                        limit ?
                  ),
                  open_order_flow as (
                       select o.symbol,
                              count(*) as open_order_count,
                              sum(case when o.side = 'BUY' then 1 else 0 end) as open_buy_order_count,
                              sum(case when o.side = 'SELL' then 1 else 0 end) as open_sell_order_count,
                              sum(case when o.side = 'BUY' then o.reserved_cash else 0 end) as reserved_buy_cash
                         from stock_order o
                         join selected_symbols s on s.symbol = o.symbol
                        where o.market_type = 'ORDER_BOOK'
                          and o.status in ('PENDING', 'PARTIALLY_FILLED')
                        group by o.symbol
                  ),
                  holding_flow as (
                       select h.symbol,
                              count(distinct h.account_id) as holder_count,
                              sum(h.quantity) as holding_quantity
                         from stock_holding h
                         join selected_symbols s on s.symbol = h.symbol
                         join stock_account a on a.id = h.account_id and a.status = 'ACTIVE'
                        group by h.symbol
                  ),
                  corporate_action_flow as (
                       select c.symbol,
                              count(*) as pending_corporate_action_count
                         from stock_corporate_action c
                         join selected_symbols s on s.symbol = c.symbol
                        where c.status in ('ANNOUNCED', 'EX_RIGHTS_APPLIED')
                        group by c.symbol
                  )
                select
                """ + symbolFlowSelectColumns(fallbackToInitialPrice) + """
                  from selected_symbols s
                  join stock_order_book_instrument i on i.symbol = s.symbol
                  left join stock_order_book_market_config m on m.symbol = i.symbol
                  left join price_source on price_source.symbol = i.symbol
                  left join execution_flow e on e.symbol = i.symbol
                  left join open_order_flow o on o.symbol = i.symbol
                  left join holding_flow h on h.symbol = i.symbol
                  left join corporate_action_flow c on c.symbol = i.symbol
                """ + SYMBOL_FLOW_ORDER_BY_SQL + """
                """;
        JdbcClient.StatementSpec statement = bindPriceSnapshotTradeDate(jdbcClient.sql(sql), priceSnapshotTradeDate);
        statement = bindExecutionWindow(statement, executionWindow);
        return statement.param(limit)
                .query((rs, rowNum) -> AdminFlowResponseMapper.toSymbolFlow(rs))
                .list();
    }

    private List<AdminSymbolFlowResponse> loadAllAdminSymbolFlows(
            SymbolFlowExecutionWindow executionWindow,
            LocalDate priceSnapshotTradeDate,
            boolean fallbackToInitialPrice
    ) {
        String sql = """
                with
                """ + priceSourceCteSql(priceSnapshotTradeDate) + """
                  execution_flow as (
                       select symbol,
                              count(*) as execution_count,
                              sum(quantity) as execution_quantity,
                              sum(gross_amount) as turnover_amount,
                              sum(case when side = 'BUY' then quantity else 0 end) as buy_quantity,
                              sum(case when side = 'SELL' then quantity else 0 end) as sell_quantity,
                              sum(case when side = 'BUY' then net_amount else 0 end) as buy_net_amount,
                              sum(case when side = 'SELL' then net_amount else 0 end) as sell_net_amount,
                              max(executed_at) as last_executed_at
                         from stock_execution
                        where source = 'INTERNAL_ORDER_BOOK'
                """ + executionWindow.predicateSql() + """
                        group by symbol
                  ),
                  open_order_flow as (
                       select symbol,
                              count(*) as open_order_count,
                              sum(case when side = 'BUY' then 1 else 0 end) as open_buy_order_count,
                              sum(case when side = 'SELL' then 1 else 0 end) as open_sell_order_count,
                              sum(case when side = 'BUY' then reserved_cash else 0 end) as reserved_buy_cash
                         from stock_order
                        where market_type = 'ORDER_BOOK'
                          and status in ('PENDING', 'PARTIALLY_FILLED')
                        group by symbol
                  ),
                  holding_flow as (
                       select h.symbol,
                              count(distinct h.account_id) as holder_count,
                              sum(h.quantity) as holding_quantity
                         from stock_holding h
                         join stock_account a on a.id = h.account_id and a.status = 'ACTIVE'
                        group by h.symbol
                  ),
                  corporate_action_flow as (
                       select symbol, count(*) as pending_corporate_action_count
                         from stock_corporate_action
                        where status in ('ANNOUNCED', 'EX_RIGHTS_APPLIED')
                        group by symbol
                  )
                select
                """ + symbolFlowSelectColumns(fallbackToInitialPrice) + """
                  from stock_order_book_instrument i
                  left join stock_order_book_market_config m on m.symbol = i.symbol
                  left join price_source on price_source.symbol = i.symbol
                  left join execution_flow e on e.symbol = i.symbol
                  left join open_order_flow o on o.symbol = i.symbol
                  left join holding_flow h on h.symbol = i.symbol
                  left join corporate_action_flow c on c.symbol = i.symbol
                """ + SYMBOL_FLOW_ORDER_BY_SQL + """
                """;
        JdbcClient.StatementSpec statement = bindPriceSnapshotTradeDate(jdbcClient.sql(sql), priceSnapshotTradeDate);
        return bindExecutionWindow(statement, executionWindow)
                .query((rs, rowNum) -> AdminFlowResponseMapper.toSymbolFlow(rs))
                .list();
    }

    private List<AdminSymbolFlowDailyCumulativeResponse> loadDailyCumulativeSymbolFlows(int limit, int days, int dayOffsetStart) {
        LocalDateTime currentDayStart = simulationClockService.currentMarketDayStart();
        LocalDateTime currentTime = simulationClockService.currentMarketDateTime();
        long totalCount = limit > 0 ? countSymbols() : -1L;
        return IntStream.range(dayOffsetStart, dayOffsetStart + days)
                .mapToObj(dayOffset -> {
                    LocalDateTime rangeStart = currentDayStart.minusDays(dayOffset);
                    LocalDateTime rangeEnd = dayOffset == 0 ? currentTime : rangeStart.plusDays(1);
                    LocalDate simulationTradeDate = rangeStart.toLocalDate();
                    List<AdminSymbolFlowResponse> symbolFlows = loadDailySnapshotSymbolFlows(limit, simulationTradeDate);
                    if (symbolFlows.isEmpty()) {
                        symbolFlows = loadAdminSymbolFlows(
                                limit,
                                SymbolFlowExecutionWindow.recent(rangeStart, rangeEnd),
                                simulationTradeDate,
                                false
                        );
                    }
                    return new AdminSymbolFlowDailyCumulativeResponse(
                            simulationTradeDate,
                            rangeStart,
                            rangeEnd,
                            totalCount >= 0 ? totalCount : symbolFlows.size(),
                            symbolFlows
                    );
                })
                .toList();
    }

    private List<AdminSymbolFlowResponse> loadDailySnapshotSymbolFlows(int limit, LocalDate simulationTradeDate) {
        String limitSql = limit > 0 ? "limit ?" : "";
        String sql = """
                with latest_snapshot as (
                       select d.*
                         from stock_order_book_daily_snapshot d
                         join (
                              select symbol,
                                     max(close_run_id) as close_run_id
                                from stock_order_book_daily_snapshot
                               where simulation_trade_date = ?
                               group by symbol
                         ) latest on latest.symbol = d.symbol and latest.close_run_id = d.close_run_id
                        where d.simulation_trade_date = ?
                )
                select
                       s.symbol,
                       s.name,
                       s.enabled,
                       s.market_status,
                       s.issued_shares,
                       s.tradable_shares,
                       s.close_price as current_price,
                       s.previous_close,
                       s.execution_count,
                       s.execution_quantity,
                       s.turnover_amount,
                       s.buy_quantity,
                       s.sell_quantity,
                       s.buy_net_amount,
                       s.sell_net_amount,
                       s.open_order_count,
                       s.open_buy_order_count,
                       s.open_sell_order_count,
                       s.reserved_buy_cash,
                       s.holder_count,
                       s.holding_quantity,
                       s.pending_corporate_action_count,
                       s.last_executed_at
                  from latest_snapshot s
                """ + DAILY_SNAPSHOT_ORDER_BY_SQL + limitSql;
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param(simulationTradeDate)
                .param(simulationTradeDate);
        if (limit > 0) {
            statement = statement.param(limit);
        }
        return statement.query((rs, rowNum) -> AdminFlowResponseMapper.toSymbolFlow(rs))
                .list();
    }

    private String priceSourceCteSql(LocalDate priceSnapshotTradeDate) {
        if (priceSnapshotTradeDate == null) {
            return """
                  price_source as (
                       select symbol,
                              current_price,
                              previous_close
                         from stock_price
                  ),
            """;
        }
        return """
                  latest_daily_snapshot as (
                       select d.symbol,
                              d.close_price,
                              d.previous_close
                         from stock_order_book_daily_snapshot d
                         join (
                              select symbol,
                                     max(close_run_id) as close_run_id
                                from stock_order_book_daily_snapshot
                               where simulation_trade_date = ?
                               group by symbol
                         ) latest on latest.symbol = d.symbol and latest.close_run_id = d.close_run_id
                  ),
                  price_source as (
                       select i.symbol,
                              d.close_price as current_price,
                              d.previous_close as previous_close
                         from stock_order_book_instrument i
                         left join latest_daily_snapshot d on d.symbol = i.symbol
                  ),
            """;
    }

    private String symbolFlowSelectColumns(boolean fallbackToInitialPrice) {
        String currentPriceExpression = fallbackToInitialPrice
                ? "coalesce(price_source.current_price, i.initial_price)"
                : "price_source.current_price";
        String previousCloseExpression = fallbackToInitialPrice
                ? "coalesce(price_source.previous_close, i.initial_price)"
                : "price_source.previous_close";
        return SYMBOL_FLOW_SELECT_COLUMNS.formatted(currentPriceExpression, previousCloseExpression);
    }

    private JdbcClient.StatementSpec bindPriceSnapshotTradeDate(
            JdbcClient.StatementSpec statement,
            LocalDate priceSnapshotTradeDate
    ) {
        if (priceSnapshotTradeDate == null) {
            return statement;
        }
        return statement.param(priceSnapshotTradeDate);
    }

    private JdbcClient.StatementSpec bindExecutionWindow(
            JdbcClient.StatementSpec statement,
            SymbolFlowExecutionWindow executionWindow
    ) {
        if (executionWindow.unbounded()) {
            return statement;
        }
        return statement
                .param(executionWindow.rangeStart())
                .param(executionWindow.rangeEnd());
    }

    private SymbolFlowExecutionWindow symbolFlowExecutionWindow(AdminFundFlowScope scope) {
        if (scope == AdminFundFlowScope.ALL) {
            return SymbolFlowExecutionWindow.all();
        }
        return SymbolFlowExecutionWindow.recent(
                simulationClockService.currentMarketDayStart(),
                simulationClockService.currentMarketDateTime()
        );
    }

    private AdminFundFlowScope normalizeScope(AdminFundFlowScope scope) {
        return scope == null ? AdminFundFlowScope.RECENT_SIMULATION_DAY : scope;
    }

    private record SymbolFlowExecutionWindow(
            boolean unbounded,
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd
    ) {
        private static SymbolFlowExecutionWindow all() {
            return new SymbolFlowExecutionWindow(true, null, null);
        }

        private static SymbolFlowExecutionWindow recent(LocalDateTime rangeStart, LocalDateTime rangeEnd) {
            return new SymbolFlowExecutionWindow(false, rangeStart, rangeEnd);
        }

        private String predicateSql() {
            if (unbounded) {
                return "";
            }
            return """
                   and executed_at >= ?
                   and executed_at < ?
            """;
        }
    }
}
