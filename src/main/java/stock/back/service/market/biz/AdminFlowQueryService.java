package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.market.vo.AdminCashFlowPageResponse;
import stock.back.service.market.vo.AdminCorporateActionFlowSummaryResponse;
import stock.back.service.market.vo.AdminFlowOverviewResponse;
import stock.back.service.market.vo.AdminFundFlowScope;
import stock.back.service.market.vo.AdminFundFlowSummaryResponse;
import stock.back.service.market.vo.AdminOrderFlowSummaryResponse;
import stock.back.service.market.vo.AdminRecentCashFlowResponse;
import stock.back.service.market.vo.AdminSymbolFlowListResponse;
import stock.back.service.market.vo.AdminTotalAssetHistoryPageResponse;
import stock.back.service.market.vo.AdminTotalAssetHistoryPointResponse;
import stock.back.service.market.vo.AdminTotalAssetPeriodSummaryResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminFlowQueryService {

    private static final int TOTAL_ASSET_HISTORY_PAGE_SIZE = 7;

    static final String FUND_FLOW_SUMMARY_SQL = fundFlowSummarySql("", "");

    static final String FUND_FLOW_SUMMARY_RECENT_SIMULATION_DAY_SQL = fundFlowSummarySql(
            """
             where f.created_at >= ?
               and f.created_at <= ?
            """,
            """
             where e.executed_at >= ?
               and e.executed_at <= ?
            """
    );

    private static String fundFlowSummarySql(String cashFlowDatePredicate, String executionDatePredicate) {
        return """
            with active_accounts as (
                select id, cash_balance
                  from stock_account
                 where status = 'ACTIVE'
                   and user_key is not null
                   and user_key not like 'stock-listing-%%'
            )
            select
              coalesce(a.active_account_count, 0) as active_account_count,
              coalesce(a.total_cash_balance, 0) as total_cash_balance,
              coalesce(o.total_reserved_buy_cash, 0) as total_reserved_buy_cash,
              coalesce(h.total_holding_market_value, 0) as total_holding_market_value,
              coalesce(h.total_holding_quantity, 0) as total_holding_quantity,
              coalesce(h.total_reserved_sell_quantity, 0) as total_reserved_sell_quantity,
              coalesce(h.holding_position_count, 0) as holding_position_count,
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
              select coalesce(sum(reserved_cash), 0) + coalesce((
                       select sum(e.subscribed_cash_amount)
                         from stock_corporate_action_entitlement e
                         join active_accounts aa on aa.id = e.account_id
                        where e.status = 'SUBSCRIBED'
                     ), 0) as total_reserved_buy_cash
                from stock_order o
                join active_accounts aa on aa.id = o.account_id
               where o.market_type = 'ORDER_BOOK'
                 and o.side = 'BUY'
                 and o.status in ('PENDING', 'PARTIALLY_FILLED')
            ) o
            cross join (
              select sum(h.quantity * coalesce(p.current_price, h.average_price)) as total_holding_market_value,
                     sum(h.quantity) as total_holding_quantity,
                     sum(h.reserved_quantity) as total_reserved_sell_quantity,
                     sum(case when h.quantity > 0 then 1 else 0 end) as holding_position_count
                from stock_holding h
                join active_accounts aa on aa.id = h.account_id
                left join stock_price p on p.symbol = h.symbol
            ) h
            cross join (
              select sum(case when f.flow_type = 'DEPOSIT' and f.reason <> 'DIVIDEND_PAYMENT' then f.amount else 0 end) as external_deposit_amount,
                     sum(case when f.flow_type = 'WITHDRAW' and f.reason <> 'CAPITAL_INCREASE_SUBSCRIPTION' then f.amount else 0 end) as external_withdraw_amount,
                     sum(case when f.flow_type = 'DEPOSIT' and f.reason = 'DIVIDEND_PAYMENT' then f.amount else 0 end) as dividend_income_amount
                from stock_account_cash_flow f
                join active_accounts aa on aa.id = f.account_id
            %s
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
            %s
            ) e
            """.formatted(cashFlowDatePredicate, executionDatePredicate);
    }

    static final String CORPORATE_ACTION_FLOW_SUMMARY_SQL = """
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
                 and created_at <= ?
            ) t
            """;

    static final String ORDER_FLOW_SUMMARY_SQL = """
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
                 and created_at <= ?
            ) t
            """;

    private static final String CASH_FLOW_SELECT_SQL = """
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
            """;

    private final JdbcClient jdbcClient;
    private final AdminSymbolFlowQueryService adminSymbolFlowQueryService;
    private final SimulationClockService simulationClockService;

    public AdminFlowQueryService(
            JdbcTemplate jdbcTemplate,
            AdminSymbolFlowQueryService adminSymbolFlowQueryService,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.adminSymbolFlowQueryService = adminSymbolFlowQueryService;
        this.simulationClockService = simulationClockService;
    }

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
        return getAdminFlowOverview(
                symbolFlowLimit,
                includeFundFlow,
                includeSymbolFlows,
                AdminFundFlowScope.RECENT_SIMULATION_DAY,
                AdminFundFlowScope.RECENT_SIMULATION_DAY
        );
    }

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview(
            int symbolFlowLimit,
            boolean includeFundFlow,
            boolean includeSymbolFlows,
            AdminFundFlowScope fundFlowScope
    ) {
        return getAdminFlowOverview(
                symbolFlowLimit,
                includeFundFlow,
                includeSymbolFlows,
                fundFlowScope,
                AdminFundFlowScope.RECENT_SIMULATION_DAY
        );
    }

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview(
            int symbolFlowLimit,
            boolean includeFundFlow,
            boolean includeSymbolFlows,
            AdminFundFlowScope fundFlowScope,
            AdminFundFlowScope symbolFlowScope
    ) {
        LocalDateTime todayStart = todayStart();
        LocalDateTime todayEnd = simulationClockService.currentMarketDateTime();
        AdminSymbolFlowListResponse symbolFlowList = includeSymbolFlows
                ? getAdminSymbolFlows(symbolFlowLimit, symbolFlowScope)
                : new AdminSymbolFlowListResponse(adminSymbolFlowQueryService.countSymbols(), List.of());
        return new AdminFlowOverviewResponse(
                includeFundFlow ? loadAdminFundFlowSummary(normalizeFlowScope(fundFlowScope)) : null,
                loadAdminOrderFlowSummary(todayStart, todayEnd),
                loadAdminCorporateActionFlowSummary(todayStart, todayEnd),
                symbolFlowList.totalCount(),
                symbolFlowList.symbolFlows(),
                loadAdminRecentCashFlows(),
                todayEnd
        );
    }

    @Transactional(readOnly = true)
    public AdminFundFlowSummaryResponse getAdminFundFlowSummary() {
        return getAdminFundFlowSummary(AdminFundFlowScope.RECENT_SIMULATION_DAY);
    }

    @Transactional(readOnly = true)
    public AdminFundFlowSummaryResponse getAdminFundFlowSummary(AdminFundFlowScope scope) {
        return loadAdminFundFlowSummary(normalizeFlowScope(scope));
    }

    @Transactional(readOnly = true)
    public AdminSymbolFlowListResponse getAdminSymbolFlows(int symbolFlowLimit) {
        return getAdminSymbolFlows(symbolFlowLimit, AdminFundFlowScope.RECENT_SIMULATION_DAY);
    }

    @Transactional(readOnly = true)
    public AdminSymbolFlowListResponse getAdminSymbolFlows(int symbolFlowLimit, AdminFundFlowScope scope) {
        return adminSymbolFlowQueryService.getAdminSymbolFlows(symbolFlowLimit, normalizeFlowScope(scope));
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
        return adminSymbolFlowQueryService.getAdminSymbolFlows(
                symbolFlowLimit,
                normalizeFlowScope(scope),
                includeDailyCumulative,
                dailyCumulativeDays,
                dailyCumulativeDayOffset
        );
    }

    @Transactional(readOnly = true)
    public AdminCashFlowPageResponse getAdminCashFlows(int page, int size) {
        AdminCashFlowPageRequest pageRequest = AdminCashFlowPageRequest.of(page, size);
        long total = jdbcClient.sql("""
                select count(*)
                  from stock_account_cash_flow
                """)
                .query(Long.class)
                .single();
        int totalPages = pageRequest.totalPages(total);
        List<AdminRecentCashFlowResponse> content = jdbcClient.sql(CASH_FLOW_SELECT_SQL + " limit ? offset ?")
                .params(pageRequest.size(), pageRequest.offset())
                .query((rs, rowNum) -> AdminFlowResponseMapper.toRecentCashFlow(rs))
                .list();
        return new AdminCashFlowPageResponse(
                content,
                pageRequest.page(),
                pageRequest.size(),
                total,
                totalPages,
                pageRequest.hasPrevious(totalPages),
                pageRequest.hasNext(totalPages)
        );
    }

    @Transactional(readOnly = true)
    public AdminTotalAssetHistoryPageResponse getAdminTotalAssetHistory(int page) {
        AdminTotalAssetHistoryPageRequest pageRequest = AdminTotalAssetHistoryPageRequest.of(page);
        long total = jdbcClient.sql("""
                select count(distinct ps.snapshot_date)
                  from portfolio_snapshot ps
                  join stock_account a on a.id = ps.account_id
                 where a.user_key is not null
                   and a.user_key not like 'stock-listing-%'
                """)
                .query(Long.class)
                .single();
        int totalPages = pageRequest.totalPages(total);
        List<TotalAssetDailyAggregate> aggregates = jdbcClient.sql("""
                select ps.snapshot_date,
                       count(*) as account_count,
                       sum(ps.total_asset) as total_asset,
                       sum(ps.cash_balance) as cash_balance,
                       sum(ps.market_value) as market_value,
                       case when count(*) = count(ps.holding_quantity) then sum(ps.holding_quantity) end as holding_quantity,
                       case when count(*) = count(ps.reserved_sell_quantity) then sum(ps.reserved_sell_quantity) end as reserved_sell_quantity,
                       case when count(*) = count(ps.holding_position_count) then sum(ps.holding_position_count) end as holding_position_count
                  from portfolio_snapshot ps
                  join stock_account a on a.id = ps.account_id
                 where a.user_key is not null
                   and a.user_key not like 'stock-listing-%'
                 group by ps.snapshot_date
                 order by ps.snapshot_date desc
                 limit ? offset ?
                """)
                .params(pageRequest.querySize(), pageRequest.offset())
                .query((rs, rowNum) -> new TotalAssetDailyAggregate(
                        rs.getObject("snapshot_date", LocalDate.class),
                        rs.getLong("account_count"),
                        rs.getBigDecimal("total_asset"),
                        rs.getBigDecimal("cash_balance"),
                        rs.getBigDecimal("market_value"),
                        rs.getObject("holding_quantity", Long.class),
                        rs.getObject("reserved_sell_quantity", Long.class),
                        rs.getObject("holding_position_count", Long.class)
                ))
                .list();
        List<AdminTotalAssetHistoryPointResponse> content = toTotalAssetHistoryPoints(aggregates);
        return new AdminTotalAssetHistoryPageResponse(
                content,
                toTotalAssetPeriodSummary(content),
                pageRequest.page(),
                TOTAL_ASSET_HISTORY_PAGE_SIZE,
                total,
                totalPages,
                pageRequest.hasPrevious(totalPages),
                pageRequest.hasNext(totalPages)
        );
    }

    private List<AdminTotalAssetHistoryPointResponse> toTotalAssetHistoryPoints(
            List<TotalAssetDailyAggregate> aggregates
    ) {
        int contentSize = Math.min(TOTAL_ASSET_HISTORY_PAGE_SIZE, aggregates.size());
        List<AdminTotalAssetHistoryPointResponse> content = new ArrayList<>(contentSize);
        for (int index = 0; index < contentSize; index++) {
            TotalAssetDailyAggregate current = aggregates.get(index);
            BigDecimal previousTotalAsset = index + 1 < aggregates.size()
                    ? aggregates.get(index + 1).totalAsset()
                    : null;
            BigDecimal changeAmount = previousTotalAsset == null
                    ? null
                    : current.totalAsset().subtract(previousTotalAsset);
            content.add(new AdminTotalAssetHistoryPointResponse(
                    current.snapshotDate(),
                    current.accountCount(),
                    current.totalAsset(),
                    current.cashBalance(),
                    current.marketValue(),
                    current.totalAsset().subtract(current.cashBalance()).subtract(current.marketValue()),
                    current.holdingQuantity(),
                    current.reservedSellQuantity(),
                    availableHoldingQuantity(current.holdingQuantity(), current.reservedSellQuantity()),
                    current.holdingPositionCount(),
                    changeAmount,
                    percentageChange(changeAmount, previousTotalAsset)
            ));
        }
        return List.copyOf(content);
    }

    private AdminTotalAssetPeriodSummaryResponse toTotalAssetPeriodSummary(
            List<AdminTotalAssetHistoryPointResponse> content
    ) {
        if (content.isEmpty()) {
            return null;
        }
        AdminTotalAssetHistoryPointResponse newest = content.getFirst();
        AdminTotalAssetHistoryPointResponse oldest = content.getLast();
        BigDecimal changeAmount = newest.totalAsset().subtract(oldest.totalAsset());
        BigDecimal total = content.stream()
                .map(AdminTotalAssetHistoryPointResponse::totalAsset)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal highest = content.stream()
                .map(AdminTotalAssetHistoryPointResponse::totalAsset)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        BigDecimal lowest = content.stream()
                .map(AdminTotalAssetHistoryPointResponse::totalAsset)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        return new AdminTotalAssetPeriodSummaryResponse(
                oldest.snapshotDate(),
                newest.snapshotDate(),
                oldest.totalAsset(),
                newest.totalAsset(),
                changeAmount,
                percentageChange(changeAmount, oldest.totalAsset()),
                total.divide(BigDecimal.valueOf(content.size()), 2, RoundingMode.HALF_UP),
                highest,
                lowest
        );
    }

    private BigDecimal percentageChange(BigDecimal changeAmount, BigDecimal baseAmount) {
        if (changeAmount == null || baseAmount == null || baseAmount.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return changeAmount.multiply(BigDecimal.valueOf(100))
                .divide(baseAmount, 4, RoundingMode.HALF_UP);
    }

    private Long availableHoldingQuantity(Long holdingQuantity, Long reservedSellQuantity) {
        if (holdingQuantity == null || reservedSellQuantity == null) {
            return null;
        }
        return holdingQuantity - reservedSellQuantity;
    }

    private AdminFundFlowSummaryResponse loadAdminFundFlowSummary(AdminFundFlowScope scope) {
        if (scope == AdminFundFlowScope.ALL) {
            return jdbcClient.sql(FUND_FLOW_SUMMARY_SQL)
                    .query((rs, rowNum) -> AdminFlowResponseMapper.toFundFlowSummary(rs))
                    .single();
        }
        LocalDateTime rangeStart = todayStart();
        LocalDateTime rangeEnd = simulationClockService.currentMarketDateTime();
        return jdbcClient.sql(FUND_FLOW_SUMMARY_RECENT_SIMULATION_DAY_SQL)
                .param(rangeStart)
                .param(rangeEnd)
                .param(rangeStart)
                .param(rangeEnd)
                .query((rs, rowNum) -> AdminFlowResponseMapper.toFundFlowSummary(rs))
                .single();
    }

    private AdminOrderFlowSummaryResponse loadAdminOrderFlowSummary(LocalDateTime todayStart, LocalDateTime todayEnd) {
        return jdbcClient.sql(ORDER_FLOW_SUMMARY_SQL)
                .param(todayStart)
                .param(todayEnd)
                .query((rs, rowNum) -> AdminFlowResponseMapper.toOrderFlowSummary(rs))
                .single();
    }

    private AdminCorporateActionFlowSummaryResponse loadAdminCorporateActionFlowSummary(
            LocalDateTime todayStart,
            LocalDateTime todayEnd
    ) {
        return jdbcClient.sql(CORPORATE_ACTION_FLOW_SUMMARY_SQL)
                .param(todayStart)
                .param(todayEnd)
                .query((rs, rowNum) -> AdminFlowResponseMapper.toCorporateActionFlowSummary(rs))
                .single();
    }

    private List<AdminRecentCashFlowResponse> loadAdminRecentCashFlows() {
        return jdbcClient.sql(CASH_FLOW_SELECT_SQL + " limit 20")
                .query((rs, rowNum) -> AdminFlowResponseMapper.toRecentCashFlow(rs))
                .list();
    }

    private LocalDateTime todayStart() {
        return simulationClockService.currentMarketDayStart();
    }

    private AdminFundFlowScope normalizeFlowScope(AdminFundFlowScope scope) {
        return scope == null ? AdminFundFlowScope.RECENT_SIMULATION_DAY : scope;
    }

    private record TotalAssetDailyAggregate(
            LocalDate snapshotDate,
            long accountCount,
            BigDecimal totalAsset,
            BigDecimal cashBalance,
            BigDecimal marketValue,
            Long holdingQuantity,
            Long reservedSellQuantity,
            Long holdingPositionCount
    ) {
    }
}
