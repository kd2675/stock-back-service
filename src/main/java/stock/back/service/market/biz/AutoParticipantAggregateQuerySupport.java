package stock.back.service.market.biz;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class AutoParticipantAggregateQuerySupport {

    static final String OPEN_ORDER_AGGREGATE_SQL = """
                    select account_id,
                           sum(case when side = 'BUY' then reserved_cash else 0 end) as reserved_buy_cash,
                           count(*) as open_order_count,
                           sum(case when side = 'BUY' then 1 else 0 end) as open_buy_order_count,
                           sum(case when side = 'SELL' then 1 else 0 end) as open_sell_order_count,
                           sum(case when side = 'BUY' then quantity - filled_quantity else 0 end) as open_buy_quantity,
                           sum(case when side = 'SELL' then quantity - filled_quantity else 0 end) as open_sell_quantity
                      from stock_order
                     where account_id in (:accountIds)
                       and market_type = 'ORDER_BOOK'
                       and status in ('PENDING', 'PARTIALLY_FILLED')
                     group by account_id
                    """;

    static final String LAST_ORDER_AGGREGATE_SQL = """
                    select account_id,
                           max(created_at) as last_order_at
                      from stock_order
                     where account_id in (:accountIds)
                       and market_type = 'ORDER_BOOK'
                       and created_at >= :activityStart
                       and created_at <= :activityEnd
                     group by account_id
                    """;

    static final String LAST_ORDER_AGGREGATE_ALL_SQL = """
                    select account_id,
                           max(created_at) as last_order_at
                      from stock_order
                     where account_id in (:accountIds)
                       and market_type = 'ORDER_BOOK'
                       and created_at <= :activityEnd
                     group by account_id
                    """;

    static final String TODAY_EXECUTION_AGGREGATE_SQL = """
                    select account_id,
                           count(*) as today_execution_count,
                           sum(case when side = 'BUY' then quantity else 0 end) as today_buy_quantity,
                           sum(case when side = 'SELL' then quantity else 0 end) as today_sell_quantity,
                           sum(gross_amount) as today_gross_amount
                     from stock_execution
                     where account_id in (:accountIds)
                       and source = 'INTERNAL_ORDER_BOOK'
                       and executed_at >= :todayStart
                       and executed_at <= :todayEnd
                     group by account_id
                    """;

    static final String LAST_EXECUTION_AGGREGATE_SQL = """
                    select account_id,
                           max(executed_at) as last_execution_at
                      from stock_execution
                     where account_id in (:accountIds)
                       and source = 'INTERNAL_ORDER_BOOK'
                       and executed_at >= :activityStart
                       and executed_at <= :activityEnd
                     group by account_id
                    """;

    static final String LAST_EXECUTION_AGGREGATE_ALL_SQL = """
                    select account_id,
                           max(executed_at) as last_execution_at
                      from stock_execution
                     where account_id in (:accountIds)
                       and source = 'INTERNAL_ORDER_BOOK'
                       and executed_at <= :activityEnd
                     group by account_id
                    """;

    private final JdbcClient jdbcClient;

    AutoParticipantAggregateQuerySupport(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    <T extends AutoParticipantAggregateTarget> void applyAccountAggregates(
            List<Long> accountIds,
            LocalDateTime todayStart,
            ActivityWindow activityWindow,
            Map<Long, T> targetByAccountId
    ) {
        if (accountIds.isEmpty()) {
            return;
        }
        applyOrderAggregates(accountIds, activityWindow, targetByAccountId);
        applyHoldings(accountIds, targetByAccountId);
        applyNetCashFlows(accountIds, targetByAccountId);
        applyExecutionAggregates(accountIds, todayStart, activityWindow, targetByAccountId);
    }

    <T extends AutoParticipantAggregateTarget> void applyStrategyAggregates(
            List<String> userKeys,
            Map<String, T> targetByUserKey
    ) {
        if (userKeys.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                        select user_key,
                               count(*) as strategy_count,
                               sum(case when enabled = true then 1 else 0 end) as enabled_strategy_count
                          from stock_auto_participant_symbol_config
                         where user_key in (:userKeys)
                         group by user_key
                        """)
                .param("userKeys", userKeys)
                .query(rs -> {
                    T target = targetByUserKey.get(rs.getString("user_key"));
                    if (target != null) {
                        target.addStrategySummary(
                                rs.getLong("strategy_count"),
                                rs.getLong("enabled_strategy_count")
                        );
                    }
                });
    }

    private <T extends AutoParticipantAggregateTarget> void applyOrderAggregates(
            List<Long> accountIds,
            ActivityWindow activityWindow,
            Map<Long, T> targetByAccountId
    ) {
        applyOpenOrderAggregates(accountIds, targetByAccountId);
        applyLastOrderAggregates(accountIds, activityWindow, targetByAccountId);
    }

    private <T extends AutoParticipantAggregateTarget> void applyOpenOrderAggregates(
            List<Long> accountIds,
            Map<Long, T> targetByAccountId
    ) {
        jdbcClient.sql(OPEN_ORDER_AGGREGATE_SQL)
                .param("accountIds", accountIds)
                .query(rs -> {
                    T target = targetByAccountId.get(rs.getLong("account_id"));
                    if (target != null) {
                        target.addOpenOrderSummary(
                                AutoParticipantQuerySupport.zeroIfNull(rs.getBigDecimal("reserved_buy_cash")),
                                rs.getLong("open_order_count"),
                                rs.getLong("open_buy_order_count"),
                                rs.getLong("open_sell_order_count"),
                                rs.getLong("open_buy_quantity"),
                                rs.getLong("open_sell_quantity")
                        );
                    }
                });
    }

    private <T extends AutoParticipantAggregateTarget> void applyLastOrderAggregates(
            List<Long> accountIds,
            ActivityWindow activityWindow,
            Map<Long, T> targetByAccountId
    ) {
        var statement = jdbcClient.sql(activityWindow.all() ? LAST_ORDER_AGGREGATE_ALL_SQL : LAST_ORDER_AGGREGATE_SQL)
                .param("accountIds", accountIds)
                .param("activityEnd", activityWindow.end());
        if (!activityWindow.all()) {
            statement = statement.param("activityStart", activityWindow.start());
        }
        var query = statement;
        List<LastOrderAggregateRow> rows = queryListWithConnectionRetry(() -> query
                .query((rs, rowNum) -> new LastOrderAggregateRow(
                        rs.getLong("account_id"),
                        rs.getObject("last_order_at", LocalDateTime.class)
                ))
                .list());
        for (LastOrderAggregateRow row : rows) {
            T target = targetByAccountId.get(row.accountId());
            if (target != null) {
                target.recordLastOrderAt(row.lastOrderAt());
            }
        }
    }

    private <T extends AutoParticipantAggregateTarget> void applyHoldings(
            List<Long> accountIds,
            Map<Long, T> targetByAccountId
    ) {
        jdbcClient.sql("""
                        select h.account_id,
                               h.symbol,
                               sum(h.quantity) as quantity,
                               sum(coalesce(h.reserved_quantity, 0)) as reserved_quantity,
                               sum(case
                                       when h.quantity - coalesce(h.reserved_quantity, 0) > 0 then h.quantity - coalesce(h.reserved_quantity, 0)
                                       else 0
                                   end) as available_quantity,
                               sum(case
                                       when h.quantity > 0 then h.quantity * coalesce(sp.current_price, h.average_price, 0)
                                       else 0
                                   end) as market_value,
                               sum((coalesce(sp.current_price, h.average_price, 0) - coalesce(h.average_price, 0)) * h.quantity) as unrealized_profit
                          from stock_holding h
                          left join stock_price sp on sp.symbol = h.symbol
                         where h.account_id in (:accountIds)
                           and (h.quantity > 0 or coalesce(h.reserved_quantity, 0) > 0)
                         group by h.account_id, h.symbol
                        """)
                .param("accountIds", accountIds)
                .query(rs -> {
                    T target = targetByAccountId.get(rs.getLong("account_id"));
                    if (target != null) {
                        target.addHoldingSummary(
                                rs.getString("symbol"),
                                rs.getLong("quantity"),
                                rs.getLong("reserved_quantity"),
                                rs.getLong("available_quantity"),
                                AutoParticipantQuerySupport.zeroIfNull(rs.getBigDecimal("market_value")),
                                AutoParticipantQuerySupport.zeroIfNull(rs.getBigDecimal("unrealized_profit"))
                        );
                    }
                });
    }

    private <T extends AutoParticipantAggregateTarget> void applyNetCashFlows(
            List<Long> accountIds,
            Map<Long, T> targetByAccountId
    ) {
        jdbcClient.sql("""
                        select account_id,
                               sum(case
                                       when flow_type = 'DEPOSIT' and reason <> 'DIVIDEND_PAYMENT' then amount
                                       when flow_type = 'WITHDRAW' then -amount
                                       else 0
                                   end) as net_cash_flow
                          from stock_account_cash_flow
                         where account_id in (:accountIds)
                           and (flow_type = 'WITHDRAW' or (flow_type = 'DEPOSIT' and reason <> 'DIVIDEND_PAYMENT'))
                         group by account_id
                        """)
                .param("accountIds", accountIds)
                .query(rs -> {
                    T target = targetByAccountId.get(rs.getLong("account_id"));
                    if (target != null) {
                        target.addNetCashFlow(AutoParticipantQuerySupport.zeroIfNull(rs.getBigDecimal("net_cash_flow")));
                    }
                });
    }

    private <T extends AutoParticipantAggregateTarget> void applyExecutionAggregates(
            List<Long> accountIds,
            LocalDateTime todayStart,
            ActivityWindow activityWindow,
            Map<Long, T> targetByAccountId
    ) {
        applyTodayExecutionAggregates(accountIds, todayStart, activityWindow.end(), targetByAccountId);
        applyLastExecutionAggregates(accountIds, activityWindow, targetByAccountId);
    }

    private <T extends AutoParticipantAggregateTarget> void applyTodayExecutionAggregates(
            List<Long> accountIds,
            LocalDateTime todayStart,
            LocalDateTime todayEnd,
            Map<Long, T> targetByAccountId
    ) {
        jdbcClient.sql(TODAY_EXECUTION_AGGREGATE_SQL)
                .param("accountIds", accountIds)
                .param("todayStart", todayStart)
                .param("todayEnd", todayEnd)
                .query(rs -> {
                    T target = targetByAccountId.get(rs.getLong("account_id"));
                    if (target != null) {
                        target.addTodayExecutionSummary(
                                rs.getLong("today_execution_count"),
                                rs.getLong("today_buy_quantity"),
                                rs.getLong("today_sell_quantity"),
                                AutoParticipantQuerySupport.zeroIfNull(rs.getBigDecimal("today_gross_amount"))
                        );
                    }
                });
    }

    private <T extends AutoParticipantAggregateTarget> void applyLastExecutionAggregates(
            List<Long> accountIds,
            ActivityWindow activityWindow,
            Map<Long, T> targetByAccountId
    ) {
        var statement = jdbcClient.sql(activityWindow.all() ? LAST_EXECUTION_AGGREGATE_ALL_SQL : LAST_EXECUTION_AGGREGATE_SQL)
                .param("accountIds", accountIds)
                .param("activityEnd", activityWindow.end());
        if (!activityWindow.all()) {
            statement = statement.param("activityStart", activityWindow.start());
        }
        statement
                .query(rs -> {
                    T target = targetByAccountId.get(rs.getLong("account_id"));
                    if (target != null) {
                        target.recordLastExecutionAt(rs.getObject("last_execution_at", LocalDateTime.class));
                    }
                });
    }

    static LocalDateTime max(LocalDateTime currentValue, LocalDateTime nextValue) {
        if (nextValue == null || currentValue != null && !nextValue.isAfter(currentValue)) {
            return currentValue;
        }
        return nextValue;
    }

    private <T> List<T> queryListWithConnectionRetry(Supplier<List<T>> querySupplier) {
        try {
            return querySupplier.get();
        } catch (DataAccessResourceFailureException | TransientDataAccessResourceException firstFailure) {
            return querySupplier.get();
        }
    }

    private record LastOrderAggregateRow(long accountId, LocalDateTime lastOrderAt) {
    }

    record ActivityWindow(LocalDateTime start, LocalDateTime end, boolean all) {

        static ActivityWindow recent(LocalDateTime start, LocalDateTime end) {
            return new ActivityWindow(start, end, false);
        }

        static ActivityWindow allUntil(LocalDateTime end) {
            return new ActivityWindow(null, end, true);
        }
    }

}
