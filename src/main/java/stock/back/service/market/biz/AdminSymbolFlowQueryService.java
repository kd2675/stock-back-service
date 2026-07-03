package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AdminFundFlowScope;
import stock.back.service.market.vo.AdminSymbolFlowListResponse;
import stock.back.service.market.vo.AdminSymbolFlowResponse;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminSymbolFlowQueryService {

    private static final String SYMBOL_FLOW_SELECT_COLUMNS = """
                       i.symbol,
                       i.name,
                       i.enabled,
                       coalesce(m.market_status, 'CLOSED') as market_status,
                       i.issued_shares,
                       i.tradable_shares,
                       coalesce(p.current_price, i.initial_price) as current_price,
                       coalesce(p.previous_close, i.initial_price) as previous_close,
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
        int normalizedSymbolFlowLimit = Math.clamp(symbolFlowLimit, 0, 500);
        List<AdminSymbolFlowResponse> symbolFlows = loadAdminSymbolFlows(normalizedSymbolFlowLimit, normalizeScope(scope));
        long totalCount = normalizedSymbolFlowLimit > 0
                ? countSymbols()
                : symbolFlows.size();
        return new AdminSymbolFlowListResponse(totalCount, symbolFlows);
    }

    @Transactional(readOnly = true)
    public long countSymbols() {
        return stockOrderBookInstrumentRepository.count();
    }

    private List<AdminSymbolFlowResponse> loadAdminSymbolFlows(int limit, AdminFundFlowScope scope) {
        if (limit > 0) {
            return loadLimitedAdminSymbolFlows(limit, scope);
        }
        return loadAllAdminSymbolFlows(scope);
    }

    private List<AdminSymbolFlowResponse> loadLimitedAdminSymbolFlows(int limit, AdminFundFlowScope scope) {
        SymbolFlowExecutionWindow executionWindow = symbolFlowExecutionWindow(scope);
        String sql = """
                with execution_flow as (
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
                """ + SYMBOL_FLOW_SELECT_COLUMNS + """
                  from selected_symbols s
                  join stock_order_book_instrument i on i.symbol = s.symbol
                  left join stock_order_book_market_config m on m.symbol = i.symbol
                  left join stock_price p on p.symbol = i.symbol
                  left join execution_flow e on e.symbol = i.symbol
                  left join open_order_flow o on o.symbol = i.symbol
                  left join holding_flow h on h.symbol = i.symbol
                  left join corporate_action_flow c on c.symbol = i.symbol
                """ + SYMBOL_FLOW_ORDER_BY_SQL + """
                """;
        JdbcClient.StatementSpec statement = bindExecutionWindow(jdbcClient.sql(sql), executionWindow);
        return statement.param(limit)
                .query((rs, rowNum) -> AdminFlowResponseMapper.toSymbolFlow(rs))
                .list();
    }

    private List<AdminSymbolFlowResponse> loadAllAdminSymbolFlows(AdminFundFlowScope scope) {
        SymbolFlowExecutionWindow executionWindow = symbolFlowExecutionWindow(scope);
        String sql = """
                select
                """ + SYMBOL_FLOW_SELECT_COLUMNS + """
                  from stock_order_book_instrument i
                  left join stock_order_book_market_config m on m.symbol = i.symbol
                  left join stock_price p on p.symbol = i.symbol
                  left join (
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
                  ) e on e.symbol = i.symbol
                  left join (
                       select symbol,
                              count(*) as open_order_count,
                              sum(case when side = 'BUY' then 1 else 0 end) as open_buy_order_count,
                              sum(case when side = 'SELL' then 1 else 0 end) as open_sell_order_count,
                              sum(case when side = 'BUY' then reserved_cash else 0 end) as reserved_buy_cash
                         from stock_order
                        where market_type = 'ORDER_BOOK'
                          and status in ('PENDING', 'PARTIALLY_FILLED')
                        group by symbol
                  ) o on o.symbol = i.symbol
                  left join (
                       select h.symbol,
                              count(distinct h.account_id) as holder_count,
                              sum(h.quantity) as holding_quantity
                         from stock_holding h
                         join stock_account a on a.id = h.account_id and a.status = 'ACTIVE'
                        group by h.symbol
                  ) h on h.symbol = i.symbol
                  left join (
                       select symbol, count(*) as pending_corporate_action_count
                         from stock_corporate_action
                        where status in ('ANNOUNCED', 'EX_RIGHTS_APPLIED')
                        group by symbol
                  ) c on c.symbol = i.symbol
                """ + SYMBOL_FLOW_ORDER_BY_SQL + """
                """;
        return bindExecutionWindow(jdbcClient.sql(sql), executionWindow)
                .query((rs, rowNum) -> AdminFlowResponseMapper.toSymbolFlow(rs))
                .list();
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
                   and executed_at <= ?
            """;
        }
    }
}
