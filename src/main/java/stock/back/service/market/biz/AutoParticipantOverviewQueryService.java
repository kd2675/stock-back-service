package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.market.vo.AutoParticipantHoldingGroupResponse;
import stock.back.service.market.vo.AutoParticipantHoldingResponse;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileSymbolHoldingResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class AutoParticipantOverviewQueryService {

    private final JdbcTemplate jdbcTemplate;

    public AutoParticipantOverviewQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantProfileOverviewResponse> getAutoParticipantProfileOverviews() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        String sql = """
                with scoped_participants as (
                    select p.user_key,
                           p.enabled,
                           p.profile_type,
                           a.id as account_id,
                           coalesce(a.cash_balance, 0) as available_cash
                      from stock_auto_participant p
                      left join stock_account a on a.user_key = p.user_key
                     where p.withdrawn_at is null
                ),
                holding_rows as (
                    select hp.profile_type,
                           h.account_id,
                           h.symbol,
                           h.quantity,
                           coalesce(h.reserved_quantity, 0) as reserved_quantity,
                           coalesce(h.average_price, 0) as average_price,
                           coalesce(sp.current_price, h.average_price, 0) as current_price
                      from stock_holding h
                      join scoped_participants hp on hp.account_id = h.account_id
                      left join stock_price sp on sp.symbol = h.symbol
                     where h.quantity > 0 or coalesce(h.reserved_quantity, 0) > 0
                )
                select p.profile_type,
                       p.total_count,
                       p.enabled_count,
                       p.account_count,
                       p.available_cash,
                       coalesce(o.reserved_buy_cash, 0) as reserved_buy_cash,
                       coalesce(h.holding_market_value, 0) as holding_market_value,
                       coalesce(f.net_cash_flow, 0) as net_cash_flow,
                       coalesce(h.holding_count, 0) as holding_count,
                       coalesce(h.total_holding_quantity, 0) as total_holding_quantity,
                       coalesce(h.reserved_sell_quantity, 0) as reserved_sell_quantity,
                       coalesce(o.open_order_count, 0) as open_order_count,
                       coalesce(o.open_buy_order_count, 0) as open_buy_order_count,
                       coalesce(o.open_sell_order_count, 0) as open_sell_order_count,
                       coalesce(o.open_buy_quantity, 0) as open_buy_quantity,
                       coalesce(o.open_sell_quantity, 0) as open_sell_quantity,
                       coalesce(e.today_execution_count, 0) as today_execution_count,
                       coalesce(e.today_buy_quantity, 0) as today_buy_quantity,
                       coalesce(e.today_sell_quantity, 0) as today_sell_quantity,
                       coalesce(e.today_gross_amount, 0) as today_gross_amount,
                       coalesce(sc.strategy_count, 0) as strategy_count,
                       coalesce(sc.enabled_strategy_count, 0) as enabled_strategy_count,
                       lo.last_order_at,
                       le.last_execution_at,
                       sh.symbol as holding_symbol,
                       sh.holder_count as holding_holder_count,
                       sh.quantity as holding_quantity,
                       sh.reserved_quantity as holding_reserved_quantity,
                       sh.available_quantity as holding_available_quantity,
                       sh.market_value as holding_market_value_detail,
                       sh.unrealized_profit as holding_unrealized_profit
                  from (
                       select profile_type,
                              count(*) as total_count,
                              sum(case when enabled = true then 1 else 0 end) as enabled_count,
                              count(account_id) as account_count,
                              coalesce(sum(available_cash), 0) as available_cash
                         from scoped_participants
                        group by profile_type
                  ) p
                  left join (
                       select op.profile_type,
                              sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'BUY' then o.reserved_cash else 0 end) as reserved_buy_cash,
                              sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') then 1 else 0 end) as open_order_count,
                              sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'BUY' then 1 else 0 end) as open_buy_order_count,
                              sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'SELL' then 1 else 0 end) as open_sell_order_count,
                              sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'BUY' then o.quantity - o.filled_quantity else 0 end) as open_buy_quantity,
                              sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'SELL' then o.quantity - o.filled_quantity else 0 end) as open_sell_quantity
                         from stock_order o
                         join scoped_participants op on op.account_id = o.account_id
                        where o.market_type = 'ORDER_BOOK'
                          and o.status in ('PENDING', 'PARTIALLY_FILLED')
                        group by op.profile_type
                  ) o on o.profile_type = p.profile_type
                  left join (
                       select profile_type,
                              max(last_order_at) as last_order_at
                         from (
                              select sp.profile_type,
                                     (
                                         select max(o.created_at)
                                           from stock_order o
                                          where o.account_id = sp.account_id
                                            and o.market_type = 'ORDER_BOOK'
                                     ) as last_order_at
                                from scoped_participants sp
                               where sp.account_id is not null
                         ) last_orders
                        group by profile_type
                  ) lo on lo.profile_type = p.profile_type
                  left join (
                       select profile_type,
                              sum(case when quantity > 0 then quantity * current_price else 0 end) as holding_market_value,
                              sum(case when quantity > 0 then 1 else 0 end) as holding_count,
                              sum(quantity) as total_holding_quantity,
                              sum(reserved_quantity) as reserved_sell_quantity
                         from holding_rows
                        group by profile_type
                  ) h on h.profile_type = p.profile_type
                  left join (
                       select fp.profile_type,
                              sum(case
                                      when f.flow_type = 'DEPOSIT' and f.reason <> 'DIVIDEND_PAYMENT' then f.amount
                                      when f.flow_type = 'WITHDRAW' then -f.amount
                                      else 0
                                  end) as net_cash_flow
                         from stock_account_cash_flow f
                         join scoped_participants fp on fp.account_id = f.account_id
                        group by fp.profile_type
                  ) f on f.profile_type = p.profile_type
                  left join (
                       select ep.profile_type,
                              count(*) as today_execution_count,
                              sum(case when e.side = 'BUY' then e.quantity else 0 end) as today_buy_quantity,
                              sum(case when e.side = 'SELL' then e.quantity else 0 end) as today_sell_quantity,
                              sum(e.gross_amount) as today_gross_amount
                         from stock_execution e
                         join scoped_participants ep on ep.account_id = e.account_id
                        where e.source = 'INTERNAL_ORDER_BOOK'
                          and e.executed_at >= ?
                        group by ep.profile_type
                  ) e on e.profile_type = p.profile_type
                  left join (
                       select profile_type,
                              max(last_execution_at) as last_execution_at
                         from (
                              select sp.profile_type,
                                     (
                                         select max(e.executed_at)
                                           from stock_execution e
                                          where e.account_id = sp.account_id
                                            and e.source = 'INTERNAL_ORDER_BOOK'
                                     ) as last_execution_at
                                from scoped_participants sp
                               where sp.account_id is not null
                         ) last_executions
                        group by profile_type
                  ) le on le.profile_type = p.profile_type
                  left join (
                       select sp.profile_type,
                              count(*) as strategy_count,
                              sum(case when sc.enabled = true then 1 else 0 end) as enabled_strategy_count
                         from stock_auto_participant_symbol_config sc
                         join scoped_participants sp on sp.user_key = sc.user_key
                        group by sp.profile_type
                  ) sc on sc.profile_type = p.profile_type
                  left join (
                       select ranked.*
                         from (
                              select grouped.*,
                                     row_number() over(partition by grouped.profile_type order by grouped.market_value desc, grouped.symbol asc) as holding_rank
                                from (
                                     select profile_type,
                                            symbol,
                                            count(distinct account_id) as holder_count,
                                            sum(quantity) as quantity,
                                            sum(reserved_quantity) as reserved_quantity,
                                            sum(case
                                                    when quantity - reserved_quantity > 0 then quantity - reserved_quantity
                                                    else 0
                                                end) as available_quantity,
                                            sum(current_price * quantity) as market_value,
                                            sum((current_price - average_price) * quantity) as unrealized_profit
                                       from holding_rows
                                      group by profile_type, symbol
                                ) grouped
                         ) ranked
                        where holding_rank <= 3
                  ) sh on sh.profile_type = p.profile_type
                 order by p.profile_type asc, sh.holding_rank asc
                """;
        return jdbcTemplate.query(sql, rs -> {
            List<AutoParticipantProfileOverviewResponse> responses = new ArrayList<>();
            AutoParticipantProfileOverviewResponse currentOverview = null;
            String currentProfileType = null;
            List<AutoParticipantProfileSymbolHoldingResponse> currentSymbolHoldings = new ArrayList<>();
            while (rs.next()) {
                String profileType = rs.getString("profile_type");
                if (!profileType.equals(currentProfileType)) {
                    if (currentOverview != null) {
                        responses.add(withAutoParticipantProfileSymbolHoldings(currentOverview, currentSymbolHoldings));
                    }
                    currentProfileType = profileType;
                    currentOverview = toAutoParticipantProfileOverviewResponse(rs, List.of());
                    currentSymbolHoldings = new ArrayList<>();
                }
                AutoParticipantProfileSymbolHoldingResponse symbolHolding = toAutoParticipantProfileSymbolHoldingResponse(rs);
                if (symbolHolding != null) {
                    currentSymbolHoldings.add(symbolHolding);
                }
            }
            if (currentOverview != null) {
                responses.add(withAutoParticipantProfileSymbolHoldings(currentOverview, currentSymbolHoldings));
            }
            return responses;
        }, todayStart);
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantOverviewResponse> getAutoParticipantOverviews(boolean includeHoldings, List<String> userKeys) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        List<String> normalizedUserKeys = normalizeAutoParticipantUserKeys(userKeys);
        String userKeyPlaceholders = sqlPlaceholders(normalizedUserKeys.size());
        String participantUserFilter = normalizedUserKeys.isEmpty() ? "" : " and p.user_key in (" + userKeyPlaceholders + ")";
        String sql = """
                with scoped_participants as (
                    select p.user_key,
                           p.display_name,
                           p.enabled,
                           p.profile_type,
                           p.created_at,
                           p.updated_at,
                           p.withdrawn_at,
                           a.id as account_id,
                           a.status as account_status,
                           coalesce(a.cash_balance, 0) as available_cash
                      from stock_auto_participant p
                      left join stock_account a on a.user_key = p.user_key
                     where p.withdrawn_at is null
                     %s
                )
                select sp.user_key,
                       sp.display_name,
                       sp.enabled,
                       sp.profile_type,
                       sp.created_at,
                       sp.updated_at,
                       sp.withdrawn_at,
                       sp.account_id,
                       sp.account_status,
                       sp.available_cash,
                       coalesce(o.reserved_buy_cash, 0) as reserved_buy_cash,
                       coalesce(h.holding_market_value, 0) as holding_market_value,
                       coalesce(f.net_cash_flow, 0) as net_cash_flow,
                       coalesce(h.holding_count, 0) as holding_count,
                       coalesce(h.total_holding_quantity, 0) as total_holding_quantity,
                       coalesce(h.reserved_sell_quantity, 0) as reserved_sell_quantity,
                       coalesce(o.open_order_count, 0) as open_order_count,
                       coalesce(o.open_buy_order_count, 0) as open_buy_order_count,
                       coalesce(o.open_sell_order_count, 0) as open_sell_order_count,
                       coalesce(o.open_buy_quantity, 0) as open_buy_quantity,
                       coalesce(o.open_sell_quantity, 0) as open_sell_quantity,
                       coalesce(e.today_execution_count, 0) as today_execution_count,
                       coalesce(e.today_buy_quantity, 0) as today_buy_quantity,
                       coalesce(e.today_sell_quantity, 0) as today_sell_quantity,
                       coalesce(e.today_gross_amount, 0) as today_gross_amount,
                       coalesce(sc.strategy_count, 0) as strategy_count,
                       coalesce(sc.enabled_strategy_count, 0) as enabled_strategy_count,
                       o.last_order_at,
                       e.last_execution_at
                from scoped_participants sp
                left join (
                    select o.account_id,
                           sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'BUY' then o.reserved_cash else 0 end) as reserved_buy_cash,
                           sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') then 1 else 0 end) as open_order_count,
                           sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'BUY' then 1 else 0 end) as open_buy_order_count,
                           sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'SELL' then 1 else 0 end) as open_sell_order_count,
                           sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'BUY' then o.quantity - o.filled_quantity else 0 end) as open_buy_quantity,
                           sum(case when o.status in ('PENDING', 'PARTIALLY_FILLED') and o.side = 'SELL' then o.quantity - o.filled_quantity else 0 end) as open_sell_quantity,
                           max(o.created_at) as last_order_at
                      from stock_order o
                      join scoped_participants op on op.account_id = o.account_id
                     where o.market_type = 'ORDER_BOOK'
                     group by o.account_id
                ) o on o.account_id = sp.account_id
                left join (
                    select h.account_id,
                           sum(case when h.quantity > 0 then h.quantity * coalesce(sp.current_price, h.average_price, 0) else 0 end) as holding_market_value,
                           sum(case when h.quantity > 0 then 1 else 0 end) as holding_count,
                           sum(h.quantity) as total_holding_quantity,
                           sum(h.reserved_quantity) as reserved_sell_quantity
                      from stock_holding h
                      join scoped_participants hp on hp.account_id = h.account_id
                      left join stock_price sp on sp.symbol = h.symbol
                     group by h.account_id
                ) h on h.account_id = sp.account_id
                left join (
                    select f.account_id,
                           sum(case
                                   when f.flow_type = 'DEPOSIT' and f.reason <> 'DIVIDEND_PAYMENT' then f.amount
                                   when f.flow_type = 'WITHDRAW' then -f.amount
                                   else 0
                               end) as net_cash_flow
                      from stock_account_cash_flow f
                      join scoped_participants fp on fp.account_id = f.account_id
                     group by f.account_id
                ) f on f.account_id = sp.account_id
                left join (
                    select e.account_id,
                           sum(case when e.executed_at >= ? then 1 else 0 end) as today_execution_count,
                           sum(case when e.executed_at >= ? and e.side = 'BUY' then e.quantity else 0 end) as today_buy_quantity,
                           sum(case when e.executed_at >= ? and e.side = 'SELL' then e.quantity else 0 end) as today_sell_quantity,
                           sum(case when e.executed_at >= ? then e.gross_amount else 0 end) as today_gross_amount,
                           max(e.executed_at) as last_execution_at
                      from stock_execution e
                      join scoped_participants ep on ep.account_id = e.account_id
                     where e.source = 'INTERNAL_ORDER_BOOK'
                     group by e.account_id
                ) e on e.account_id = sp.account_id
                left join (
                    select sc.user_key,
                           count(*) as strategy_count,
                           sum(case when sc.enabled = true then 1 else 0 end) as enabled_strategy_count
                      from stock_auto_participant_symbol_config sc
                      join scoped_participants spc on spc.user_key = sc.user_key
                     group by sc.user_key
                ) sc on sc.user_key = sp.user_key
                order by sp.user_key asc
                """.formatted(participantUserFilter);
        List<Object> queryArguments = new ArrayList<>();
        queryArguments.addAll(normalizedUserKeys);
        queryArguments.add(todayStart);
        queryArguments.add(todayStart);
        queryArguments.add(todayStart);
        queryArguments.add(todayStart);
        List<AutoParticipantOverviewResponse> overviews = jdbcTemplate.query(sql, (rs, rowNum) -> toAutoParticipantOverviewResponse(rs), queryArguments.toArray());
        if (!includeHoldings) {
            return overviews;
        }
        Map<Long, List<AutoParticipantHoldingResponse>> holdingsByAccountId = findAutoParticipantHoldings(overviews.stream()
                .map(AutoParticipantOverviewResponse::accountId)
                .filter(accountId -> accountId != null)
                .distinct()
                .toList());
        return overviews.stream()
                .map(overview -> withAutoParticipantHoldings(
                        overview,
                        overview.accountId() == null ? List.of() : holdingsByAccountId.getOrDefault(overview.accountId(), List.of())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantHoldingGroupResponse> getAutoParticipantHoldings(List<String> userKeys) {
        List<String> normalizedUserKeys = normalizeAutoParticipantUserKeys(userKeys);
        if (normalizedUserKeys.isEmpty()) {
            return List.of();
        }
        String requestedUserRows = IntStream.range(0, normalizedUserKeys.size())
                .mapToObj(index -> index == 0
                        ? "select concat('', ?) as user_key, (? + 0) as request_order"
                        : "select concat('', ?), (? + 0)")
                .collect(Collectors.joining("\n union all\n"));
        String sql = """
                select r.user_key,
                       a.id as account_id,
                       h.symbol,
                       h.quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       case
                           when h.quantity - coalesce(h.reserved_quantity, 0) > 0 then h.quantity - coalesce(h.reserved_quantity, 0)
                           else 0
                       end as available_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(sp.current_price, h.average_price, 0) as current_price,
                       coalesce(sp.current_price, h.average_price, 0) * h.quantity as market_value,
                       (coalesce(sp.current_price, h.average_price, 0) - coalesce(h.average_price, 0)) * h.quantity as unrealized_profit
                  from (
                %s
                  ) r
                  join stock_auto_participant p on p.user_key = r.user_key and p.withdrawn_at is null
                  left join stock_account a on a.user_key = p.user_key
                  left join stock_holding h
                    on h.account_id = a.id
                   and (h.quantity > 0 or coalesce(h.reserved_quantity, 0) > 0)
                  left join stock_price sp on sp.symbol = h.symbol
                 order by r.request_order asc, h.symbol asc
                """.formatted(requestedUserRows);
        List<Object> queryArguments = new ArrayList<>();
        for (int index = 0; index < normalizedUserKeys.size(); index++) {
            queryArguments.add(normalizedUserKeys.get(index));
            queryArguments.add(index);
        }
        return jdbcTemplate.query(sql, rs -> {
            List<AutoParticipantHoldingGroupResponse> responses = new ArrayList<>();
            String currentUserKey = null;
            Long currentAccountId = null;
            List<AutoParticipantHoldingResponse> currentHoldings = new ArrayList<>();
            while (rs.next()) {
                String userKey = rs.getString("user_key");
                if (!userKey.equals(currentUserKey)) {
                    if (currentUserKey != null) {
                        responses.add(new AutoParticipantHoldingGroupResponse(currentUserKey, currentAccountId, currentHoldings));
                    }
                    currentUserKey = userKey;
                    currentAccountId = rs.getObject("account_id", Long.class);
                    currentHoldings = new ArrayList<>();
                }
                String symbol = rs.getString("symbol");
                if (symbol != null) {
                    currentHoldings.add(new AutoParticipantHoldingResponse(
                            symbol,
                            rs.getLong("quantity"),
                            rs.getLong("reserved_quantity"),
                            rs.getLong("available_quantity"),
                            nonNullDecimal(rs.getBigDecimal("average_price")),
                            nonNullDecimal(rs.getBigDecimal("current_price")),
                            nonNullDecimal(rs.getBigDecimal("market_value")),
                            nonNullDecimal(rs.getBigDecimal("unrealized_profit"))
                    ));
                }
            }
            if (currentUserKey != null) {
                responses.add(new AutoParticipantHoldingGroupResponse(currentUserKey, currentAccountId, currentHoldings));
            }
            return responses;
        }, queryArguments.toArray());
    }

    private AutoParticipantOverviewResponse toAutoParticipantOverviewResponse(ResultSet rs) throws SQLException {
        BigDecimal availableCash = nonNullDecimal(rs.getBigDecimal("available_cash"));
        BigDecimal reservedBuyCash = nonNullDecimal(rs.getBigDecimal("reserved_buy_cash"));
        BigDecimal holdingMarketValue = nonNullDecimal(rs.getBigDecimal("holding_market_value"));
        BigDecimal estimatedTotalAsset = availableCash.add(reservedBuyCash).add(holdingMarketValue);
        BigDecimal netCashFlow = nonNullDecimal(rs.getBigDecimal("net_cash_flow"));
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal returnRate = BigDecimal.ZERO;
        if (netCashFlow.compareTo(BigDecimal.ZERO) > 0) {
            totalProfit = estimatedTotalAsset.subtract(netCashFlow);
            returnRate = totalProfit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(netCashFlow, 4, RoundingMode.HALF_UP);
        }
        return new AutoParticipantOverviewResponse(
                rs.getString("user_key"),
                rs.getString("display_name"),
                rs.getBoolean("enabled"),
                rs.getString("profile_type"),
                rs.getObject("account_id", Long.class),
                rs.getString("account_status"),
                availableCash,
                reservedBuyCash,
                holdingMarketValue,
                estimatedTotalAsset,
                netCashFlow,
                totalProfit,
                returnRate,
                rs.getLong("holding_count"),
                rs.getLong("total_holding_quantity"),
                rs.getLong("reserved_sell_quantity"),
                List.of(),
                rs.getLong("open_order_count"),
                rs.getLong("open_buy_order_count"),
                rs.getLong("open_sell_order_count"),
                rs.getLong("open_buy_quantity"),
                rs.getLong("open_sell_quantity"),
                rs.getLong("today_execution_count"),
                rs.getLong("today_buy_quantity"),
                rs.getLong("today_sell_quantity"),
                nonNullDecimal(rs.getBigDecimal("today_gross_amount")),
                rs.getLong("strategy_count"),
                rs.getLong("enabled_strategy_count"),
                rs.getObject("last_order_at", LocalDateTime.class),
                rs.getObject("last_execution_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("withdrawn_at", LocalDateTime.class)
        );
    }

    private AutoParticipantProfileOverviewResponse toAutoParticipantProfileOverviewResponse(
            ResultSet rs,
            List<AutoParticipantProfileSymbolHoldingResponse> symbolHoldings
    ) throws SQLException {
        BigDecimal availableCash = nonNullDecimal(rs.getBigDecimal("available_cash"));
        BigDecimal reservedBuyCash = nonNullDecimal(rs.getBigDecimal("reserved_buy_cash"));
        BigDecimal holdingMarketValue = nonNullDecimal(rs.getBigDecimal("holding_market_value"));
        BigDecimal estimatedTotalAsset = availableCash.add(reservedBuyCash).add(holdingMarketValue);
        BigDecimal netCashFlow = nonNullDecimal(rs.getBigDecimal("net_cash_flow"));
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal returnRate = BigDecimal.ZERO;
        if (netCashFlow.compareTo(BigDecimal.ZERO) > 0) {
            totalProfit = estimatedTotalAsset.subtract(netCashFlow);
            returnRate = totalProfit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(netCashFlow, 4, RoundingMode.HALF_UP);
        }
        long totalCount = rs.getLong("total_count");
        long enabledCount = rs.getLong("enabled_count");
        return new AutoParticipantProfileOverviewResponse(
                rs.getString("profile_type"),
                totalCount,
                enabledCount,
                totalCount - enabledCount,
                rs.getLong("account_count"),
                availableCash,
                reservedBuyCash,
                holdingMarketValue,
                estimatedTotalAsset,
                netCashFlow,
                totalProfit,
                returnRate,
                rs.getLong("holding_count"),
                rs.getLong("total_holding_quantity"),
                rs.getLong("reserved_sell_quantity"),
                rs.getLong("open_order_count"),
                rs.getLong("open_buy_order_count"),
                rs.getLong("open_sell_order_count"),
                rs.getLong("open_buy_quantity"),
                rs.getLong("open_sell_quantity"),
                rs.getLong("today_execution_count"),
                rs.getLong("today_buy_quantity"),
                rs.getLong("today_sell_quantity"),
                nonNullDecimal(rs.getBigDecimal("today_gross_amount")),
                rs.getLong("strategy_count"),
                rs.getLong("enabled_strategy_count"),
                rs.getObject("last_order_at", LocalDateTime.class),
                rs.getObject("last_execution_at", LocalDateTime.class),
                symbolHoldings
        );
    }

    private AutoParticipantProfileOverviewResponse withAutoParticipantProfileSymbolHoldings(
            AutoParticipantProfileOverviewResponse overview,
            List<AutoParticipantProfileSymbolHoldingResponse> symbolHoldings
    ) {
        return new AutoParticipantProfileOverviewResponse(
                overview.profileType(),
                overview.totalCount(),
                overview.enabledCount(),
                overview.disabledCount(),
                overview.accountCount(),
                overview.availableCash(),
                overview.reservedBuyCash(),
                overview.holdingMarketValue(),
                overview.estimatedTotalAsset(),
                overview.netCashFlow(),
                overview.totalProfit(),
                overview.returnRate(),
                overview.holdingCount(),
                overview.totalHoldingQuantity(),
                overview.reservedSellQuantity(),
                overview.openOrderCount(),
                overview.openBuyOrderCount(),
                overview.openSellOrderCount(),
                overview.openBuyQuantity(),
                overview.openSellQuantity(),
                overview.todayExecutionCount(),
                overview.todayBuyQuantity(),
                overview.todaySellQuantity(),
                overview.todayGrossAmount(),
                overview.strategyCount(),
                overview.enabledStrategyCount(),
                overview.lastOrderAt(),
                overview.lastExecutionAt(),
                symbolHoldings
        );
    }

    private AutoParticipantProfileSymbolHoldingResponse toAutoParticipantProfileSymbolHoldingResponse(ResultSet rs) throws SQLException {
        String symbol = rs.getString("holding_symbol");
        if (symbol == null) {
            return null;
        }
        return new AutoParticipantProfileSymbolHoldingResponse(
                symbol,
                rs.getLong("holding_holder_count"),
                rs.getLong("holding_quantity"),
                rs.getLong("holding_reserved_quantity"),
                rs.getLong("holding_available_quantity"),
                nonNullDecimal(rs.getBigDecimal("holding_market_value_detail")),
                nonNullDecimal(rs.getBigDecimal("holding_unrealized_profit"))
        );
    }

    private List<String> normalizeAutoParticipantUserKeys(List<String> userKeys) {
        if (userKeys == null || userKeys.isEmpty()) {
            return List.of();
        }
        return userKeys.stream()
                .filter(userKey -> userKey != null)
                .flatMap(userKey -> Arrays.stream(userKey.split(",")))
                .map(String::trim)
                .filter(userKey -> !userKey.isBlank())
                .distinct()
                .limit(50)
                .toList();
    }

    private String sqlPlaceholders(int size) {
        return IntStream.range(0, size)
                .mapToObj(ignored -> "?")
                .collect(Collectors.joining(","));
    }

    private Map<Long, List<AutoParticipantHoldingResponse>> findAutoParticipantHoldings(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = accountIds.stream()
                .map(accountId -> "?")
                .collect(Collectors.joining(", "));
        String sql = """
                select h.account_id,
                       h.symbol,
                       h.quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       case
                           when h.quantity - coalesce(h.reserved_quantity, 0) > 0 then h.quantity - coalesce(h.reserved_quantity, 0)
                           else 0
                       end as available_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(sp.current_price, h.average_price, 0) as current_price,
                       coalesce(sp.current_price, h.average_price, 0) * h.quantity as market_value,
                       (coalesce(sp.current_price, h.average_price, 0) - coalesce(h.average_price, 0)) * h.quantity as unrealized_profit
                from stock_holding h
                left join stock_price sp on sp.symbol = h.symbol
                where h.account_id in (%s)
                  and (h.quantity > 0 or coalesce(h.reserved_quantity, 0) > 0)
                order by h.account_id asc, h.symbol asc
                """.formatted(placeholders);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AutoParticipantHoldingLedger(
                        rs.getLong("account_id"),
                        new AutoParticipantHoldingResponse(
                                rs.getString("symbol"),
                                rs.getLong("quantity"),
                                rs.getLong("reserved_quantity"),
                                rs.getLong("available_quantity"),
                                nonNullDecimal(rs.getBigDecimal("average_price")),
                                nonNullDecimal(rs.getBigDecimal("current_price")),
                                nonNullDecimal(rs.getBigDecimal("market_value")),
                                nonNullDecimal(rs.getBigDecimal("unrealized_profit"))
                        )
                ),
                accountIds.toArray()
        ).stream().collect(Collectors.groupingBy(
                AutoParticipantHoldingLedger::accountId,
                Collectors.mapping(AutoParticipantHoldingLedger::holding, Collectors.toList())
        ));
    }

    private AutoParticipantOverviewResponse withAutoParticipantHoldings(
            AutoParticipantOverviewResponse overview,
            List<AutoParticipantHoldingResponse> holdings
    ) {
        return new AutoParticipantOverviewResponse(
                overview.userKey(),
                overview.displayName(),
                overview.enabled(),
                overview.profileType(),
                overview.accountId(),
                overview.accountStatus(),
                overview.availableCash(),
                overview.reservedBuyCash(),
                overview.holdingMarketValue(),
                overview.estimatedTotalAsset(),
                overview.netCashFlow(),
                overview.totalProfit(),
                overview.returnRate(),
                overview.holdingCount(),
                overview.totalHoldingQuantity(),
                overview.reservedSellQuantity(),
                holdings,
                overview.openOrderCount(),
                overview.openBuyOrderCount(),
                overview.openSellOrderCount(),
                overview.openBuyQuantity(),
                overview.openSellQuantity(),
                overview.todayExecutionCount(),
                overview.todayBuyQuantity(),
                overview.todaySellQuantity(),
                overview.todayGrossAmount(),
                overview.strategyCount(),
                overview.enabledStrategyCount(),
                overview.lastOrderAt(),
                overview.lastExecutionAt(),
                overview.createdAt(),
                overview.updatedAt(),
                overview.withdrawnAt()
        );
    }

    private BigDecimal nonNullDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record AutoParticipantHoldingLedger(
            Long accountId,
            AutoParticipantHoldingResponse holding
    ) {
    }
}
