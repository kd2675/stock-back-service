package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AdminCashFlowPageResponse;
import stock.back.service.market.vo.AdminCorporateActionFlowSummaryResponse;
import stock.back.service.market.vo.AdminFlowOverviewResponse;
import stock.back.service.market.vo.AdminFundFlowSummaryResponse;
import stock.back.service.market.vo.AdminOrderFlowSummaryResponse;
import stock.back.service.market.vo.AdminRecentCashFlowResponse;
import stock.back.service.market.vo.AdminSymbolFlowListResponse;
import stock.back.service.market.vo.AdminSymbolFlowResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminFlowQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview() {
        return getAdminFlowOverview(0, true, true);
    }

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview(int symbolFlowLimit) {
        return getAdminFlowOverview(symbolFlowLimit, true, true);
    }

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview(int symbolFlowLimit, boolean includeFundFlow) {
        return getAdminFlowOverview(symbolFlowLimit, includeFundFlow, true);
    }

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview(int symbolFlowLimit, boolean includeFundFlow, boolean includeSymbolFlows) {
        AdminSymbolFlowListResponse symbolFlowList = includeSymbolFlows
                ? getAdminSymbolFlows(symbolFlowLimit)
                : new AdminSymbolFlowListResponse(stockOrderBookInstrumentRepository.count(), List.of());
        return new AdminFlowOverviewResponse(
                includeFundFlow ? loadAdminFundFlowSummary() : null,
                loadAdminOrderFlowSummary(),
                loadAdminCorporateActionFlowSummary(),
                symbolFlowList.totalCount(),
                symbolFlowList.symbolFlows(),
                loadAdminRecentCashFlows(),
                LocalDateTime.now()
        );
    }

    @Transactional(readOnly = true)
    public AdminFundFlowSummaryResponse getAdminFundFlowSummary() {
        return loadAdminFundFlowSummary();
    }

    @Transactional(readOnly = true)
    public AdminSymbolFlowListResponse getAdminSymbolFlows(int symbolFlowLimit) {
        int normalizedSymbolFlowLimit = Math.max(0, Math.min(500, symbolFlowLimit));
        List<AdminSymbolFlowResponse> symbolFlows = loadAdminSymbolFlows(normalizedSymbolFlowLimit);
        long totalCount = normalizedSymbolFlowLimit > 0
                ? stockOrderBookInstrumentRepository.count()
                : symbolFlows.size();
        return new AdminSymbolFlowListResponse(totalCount, symbolFlows);
    }

    @Transactional(readOnly = true)
    public AdminCashFlowPageResponse getAdminCashFlows(int page, int size) {
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.max(1, Math.min(100, size));
        int offset = normalizedPage > Integer.MAX_VALUE / normalizedSize
                ? Integer.MAX_VALUE
                : normalizedPage * normalizedSize;
        Long totalElements = jdbcTemplate.queryForObject("""
                select count(*)
                  from stock_account_cash_flow
                """, Long.class);
        long total = totalElements == null ? 0L : totalElements;
        int totalPages = total == 0L ? 0 : (int) Math.ceil((double) total / normalizedSize);
        List<AdminRecentCashFlowResponse> content = jdbcTemplate.query("""
                select f.id,
                       a.id as account_id,
                       a.user_key,
                       f.flow_type,
                       f.amount,
                       f.reason,
                       f.created_by,
                       f.created_at
                  from stock_account_cash_flow f
                  join stock_account a on a.id = f.account_id
                 order by f.created_at desc, f.id desc
                 limit ? offset ?
                """, (rs, rowNum) -> toAdminRecentCashFlowResponse(rs), normalizedSize, offset);
        return new AdminCashFlowPageResponse(
                content,
                normalizedPage,
                normalizedSize,
                total,
                totalPages,
                normalizedPage > 0 && totalPages > 0,
                normalizedPage + 1 < totalPages
        );
    }

    private AdminFundFlowSummaryResponse loadAdminFundFlowSummary() {
        String sql = """
                with active_accounts as (
                    select id, cash_balance
                      from stock_account
                     where status = 'ACTIVE'
                )
                select
                  coalesce(a.active_account_count, 0) as active_account_count,
                  coalesce(a.total_cash_balance, 0) as total_cash_balance,
                  coalesce(o.total_reserved_buy_cash, 0) as total_reserved_buy_cash,
                  coalesce(h.total_holding_market_value, 0) as total_holding_market_value,
                  coalesce(f.external_deposit_amount, 0) as external_deposit_amount,
                  coalesce(f.external_withdraw_amount, 0) as external_withdraw_amount,
                  coalesce(f.dividend_income_amount, 0) as dividend_income_amount,
                  coalesce(e.buy_net_amount, 0) as buy_net_amount,
                  coalesce(e.sell_net_amount, 0) as sell_net_amount,
                  coalesce(e.total_fee_amount, 0) as total_fee_amount,
                  coalesce(e.total_tax_amount, 0) as total_tax_amount,
                  coalesce(e.realized_profit, 0) as realized_profit,
                  coalesce(e.execution_count, 0) as execution_count
                from (
                  select count(*) as active_account_count,
                         sum(cash_balance) as total_cash_balance
                    from active_accounts
                ) a
                cross join (
                  select sum(reserved_cash) as total_reserved_buy_cash
                    from stock_order
                   where market_type = 'ORDER_BOOK'
                     and side = 'BUY'
                     and status in ('PENDING', 'PARTIALLY_FILLED')
                ) o
                cross join (
                  select sum(h.quantity * coalesce(p.current_price, h.average_price)) as total_holding_market_value
                    from stock_holding h
                    join active_accounts aa on aa.id = h.account_id
                    left join stock_price p on p.symbol = h.symbol
                ) h
                cross join (
                  select sum(case when f.flow_type = 'DEPOSIT' and f.reason <> 'DIVIDEND_PAYMENT' then f.amount else 0 end) as external_deposit_amount,
                         sum(case when f.flow_type = 'WITHDRAW' then f.amount else 0 end) as external_withdraw_amount,
                         sum(case when f.flow_type = 'DEPOSIT' and f.reason = 'DIVIDEND_PAYMENT' then f.amount else 0 end) as dividend_income_amount
                    from stock_account_cash_flow f
                    join active_accounts aa on aa.id = f.account_id
                ) f
                cross join (
                  select sum(case when e.side = 'BUY' then e.net_amount else 0 end) as buy_net_amount,
                         sum(case when e.side = 'SELL' then e.net_amount else 0 end) as sell_net_amount,
                         sum(e.fee_amount) as total_fee_amount,
                         sum(e.tax_amount) as total_tax_amount,
                         sum(e.realized_profit) as realized_profit,
                         count(*) as execution_count
                    from stock_execution e
                    join active_accounts aa on aa.id = e.account_id
                ) e
                """;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
            BigDecimal totalCashBalance = rs.getBigDecimal("total_cash_balance");
            BigDecimal totalReservedBuyCash = rs.getBigDecimal("total_reserved_buy_cash");
            BigDecimal totalHoldingMarketValue = rs.getBigDecimal("total_holding_market_value");
            BigDecimal externalDepositAmount = rs.getBigDecimal("external_deposit_amount");
            BigDecimal externalWithdrawAmount = rs.getBigDecimal("external_withdraw_amount");
            BigDecimal dividendIncomeAmount = rs.getBigDecimal("dividend_income_amount");
            BigDecimal buyNetAmount = rs.getBigDecimal("buy_net_amount");
            BigDecimal sellNetAmount = rs.getBigDecimal("sell_net_amount");
            return new AdminFundFlowSummaryResponse(
                    rs.getLong("active_account_count"),
                    totalCashBalance,
                    totalReservedBuyCash,
                    totalHoldingMarketValue,
                    totalCashBalance.add(totalReservedBuyCash).add(totalHoldingMarketValue),
                    externalDepositAmount,
                    externalWithdrawAmount,
                    externalDepositAmount.subtract(externalWithdrawAmount),
                    dividendIncomeAmount,
                    buyNetAmount,
                    sellNetAmount,
                    sellNetAmount.subtract(buyNetAmount),
                    rs.getBigDecimal("total_fee_amount"),
                    rs.getBigDecimal("total_tax_amount"),
                    rs.getBigDecimal("realized_profit"),
                    rs.getLong("execution_count")
            );
        });
    }

    private AdminOrderFlowSummaryResponse loadAdminOrderFlowSummary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        String sql = """
                select
                  coalesce(o.open_order_count, 0) as open_order_count,
                  coalesce(o.open_buy_order_count, 0) as open_buy_order_count,
                  coalesce(o.open_sell_order_count, 0) as open_sell_order_count,
                  coalesce(o.partially_filled_order_count, 0) as partially_filled_order_count,
                  coalesce(o.reserved_buy_cash, 0) as reserved_buy_cash,
                  coalesce(o.reserved_sell_quantity, 0) as reserved_sell_quantity,
                  coalesce(t.today_order_count, 0) as today_order_count,
                  coalesce(t.today_filled_order_count, 0) as today_filled_order_count,
                  coalesce(t.today_cancelled_order_count, 0) as today_cancelled_order_count,
                  coalesce(t.today_rejected_order_count, 0) as today_rejected_order_count
                from (
                  select count(*) as open_order_count,
                         sum(case when side = 'BUY' then 1 else 0 end) as open_buy_order_count,
                         sum(case when side = 'SELL' then 1 else 0 end) as open_sell_order_count,
                         sum(case when status = 'PARTIALLY_FILLED' then 1 else 0 end) as partially_filled_order_count,
                         sum(case when side = 'BUY' then reserved_cash else 0 end) as reserved_buy_cash,
                         sum(case when side = 'SELL' then quantity - filled_quantity else 0 end) as reserved_sell_quantity
                    from stock_order
                   where market_type = 'ORDER_BOOK'
                     and status in ('PENDING', 'PARTIALLY_FILLED')
                ) o
                cross join (
                  select count(*) as today_order_count,
                         sum(case when status = 'FILLED' then 1 else 0 end) as today_filled_order_count,
                         sum(case when status = 'CANCELLED' then 1 else 0 end) as today_cancelled_order_count,
                         sum(case when status = 'REJECTED' then 1 else 0 end) as today_rejected_order_count
                    from stock_order
                   where market_type = 'ORDER_BOOK'
                     and created_at >= ?
                ) t
                """;
        return jdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> new AdminOrderFlowSummaryResponse(
                        rs.getLong("open_order_count"),
                        rs.getLong("open_buy_order_count"),
                        rs.getLong("open_sell_order_count"),
                        rs.getLong("partially_filled_order_count"),
                        rs.getBigDecimal("reserved_buy_cash"),
                        rs.getLong("reserved_sell_quantity"),
                        rs.getLong("today_order_count"),
                        rs.getLong("today_filled_order_count"),
                        rs.getLong("today_cancelled_order_count"),
                        rs.getLong("today_rejected_order_count")
                ),
                todayStart
        );
    }

    private AdminCorporateActionFlowSummaryResponse loadAdminCorporateActionFlowSummary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        String sql = """
                select
                  coalesce(s.announced_count, 0) as announced_count,
                  coalesce(s.ex_rights_applied_count, 0) as ex_rights_applied_count,
                  coalesce(s.paid_count, 0) as paid_count,
                  coalesce(s.listed_count, 0) as listed_count,
                  coalesce(s.delisted_count, 0) as delisted_count,
                  coalesce(s.pending_count, 0) as pending_count,
                  coalesce(t.today_created_count, 0) as today_created_count
                from (
                  select sum(case when status = 'ANNOUNCED' then 1 else 0 end) as announced_count,
                         sum(case when status = 'EX_RIGHTS_APPLIED' then 1 else 0 end) as ex_rights_applied_count,
                         sum(case when status = 'PAID' then 1 else 0 end) as paid_count,
                         sum(case when status = 'LISTED' then 1 else 0 end) as listed_count,
                         sum(case when status = 'DELISTED' then 1 else 0 end) as delisted_count,
                         sum(case when status in ('ANNOUNCED', 'EX_RIGHTS_APPLIED') then 1 else 0 end) as pending_count
                    from stock_corporate_action
                ) s
                cross join (
                  select count(*) as today_created_count
                    from stock_corporate_action
                   where created_at >= ?
                ) t
                """;
        return jdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> new AdminCorporateActionFlowSummaryResponse(
                        rs.getLong("announced_count"),
                        rs.getLong("ex_rights_applied_count"),
                        rs.getLong("paid_count"),
                        rs.getLong("listed_count"),
                        rs.getLong("delisted_count"),
                        rs.getLong("pending_count"),
                        rs.getLong("today_created_count")
                ),
                todayStart
        );
    }

    private List<AdminSymbolFlowResponse> loadAdminSymbolFlows(int limit) {
        if (limit > 0) {
            return loadLimitedAdminSymbolFlows(limit);
        }
        return loadAllAdminSymbolFlows();
    }

    private List<AdminSymbolFlowResponse> loadLimitedAdminSymbolFlows(int limit) {
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
                select i.symbol,
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
                  from selected_symbols s
                  join stock_order_book_instrument i on i.symbol = s.symbol
                  left join stock_order_book_market_config m on m.symbol = i.symbol
                  left join stock_price p on p.symbol = i.symbol
                  left join execution_flow e on e.symbol = i.symbol
                  left join open_order_flow o on o.symbol = i.symbol
                  left join holding_flow h on h.symbol = i.symbol
                  left join corporate_action_flow c on c.symbol = i.symbol
                 order by coalesce(e.turnover_amount, 0) desc,
                          coalesce(e.execution_count, 0) desc,
                          i.symbol asc
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> toAdminSymbolFlowResponse(rs), limit);
    }

    private List<AdminSymbolFlowResponse> loadAllAdminSymbolFlows() {
        String sql = """
                select i.symbol,
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
                 order by coalesce(e.turnover_amount, 0) desc,
                          coalesce(e.execution_count, 0) desc,
                          i.symbol asc
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> toAdminSymbolFlowResponse(rs));
    }

    private AdminSymbolFlowResponse toAdminSymbolFlowResponse(ResultSet rs) throws SQLException {
        BigDecimal currentPrice = rs.getBigDecimal("current_price");
        BigDecimal previousClose = rs.getBigDecimal("previous_close");
        return new AdminSymbolFlowResponse(
                rs.getString("symbol"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getString("market_status"),
                rs.getLong("issued_shares"),
                rs.getLong("tradable_shares"),
                currentPrice,
                previousClose,
                calculateChangeRate(currentPrice, previousClose),
                rs.getLong("execution_count"),
                rs.getLong("execution_quantity"),
                rs.getBigDecimal("turnover_amount"),
                rs.getLong("buy_quantity"),
                rs.getLong("sell_quantity"),
                rs.getBigDecimal("buy_net_amount"),
                rs.getBigDecimal("sell_net_amount"),
                rs.getLong("open_order_count"),
                rs.getLong("open_buy_order_count"),
                rs.getLong("open_sell_order_count"),
                rs.getBigDecimal("reserved_buy_cash"),
                rs.getLong("holder_count"),
                rs.getLong("holding_quantity"),
                rs.getLong("pending_corporate_action_count"),
                rs.getTimestamp("last_executed_at") == null ? null : rs.getTimestamp("last_executed_at").toLocalDateTime()
        );
    }

    private List<AdminRecentCashFlowResponse> loadAdminRecentCashFlows() {
        String sql = """
                select f.id,
                       a.id as account_id,
                       a.user_key,
                       f.flow_type,
                       f.amount,
                       f.reason,
                       f.created_by,
                       f.created_at
                  from stock_account_cash_flow f
                 join stock_account a on a.id = f.account_id
                 order by f.created_at desc, f.id desc
                 limit 20
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> toAdminRecentCashFlowResponse(rs));
    }

    private AdminRecentCashFlowResponse toAdminRecentCashFlowResponse(ResultSet rs) throws SQLException {
        return new AdminRecentCashFlowResponse(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("user_key"),
                rs.getString("flow_type"),
                rs.getBigDecimal("amount"),
                rs.getString("reason"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private BigDecimal calculateChangeRate(BigDecimal currentPrice, BigDecimal previousClose) {
        if (currentPrice == null || previousClose == null || previousClose.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(previousClose)
                .multiply(BigDecimal.valueOf(100))
                .divide(previousClose, 4, RoundingMode.HALF_UP);
    }
}
