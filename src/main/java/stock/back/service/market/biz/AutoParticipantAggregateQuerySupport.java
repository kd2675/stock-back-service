package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

final class AutoParticipantAggregateQuerySupport {

    static final String OPEN_ORDER_AGGREGATE_SQL = """
                    select account_id,
                           sum(reserved_buy_cash) as reserved_buy_cash,
                           sum(open_order_count) as open_order_count,
                           sum(open_buy_order_count) as open_buy_order_count,
                           sum(open_sell_order_count) as open_sell_order_count,
                           sum(open_buy_quantity) as open_buy_quantity,
                           sum(open_sell_quantity) as open_sell_quantity
                      from (
                            select account_id,
                                   sum(case when side = 'BUY' then reserved_cash else 0 end) as reserved_buy_cash,
                                   count(*) as open_order_count,
                                   sum(case when side = 'BUY' then 1 else 0 end) as open_buy_order_count,
                                   sum(case when side = 'SELL' then 1 else 0 end) as open_sell_order_count,
                                   sum(case when side = 'BUY' then quantity - filled_quantity else 0 end) as open_buy_quantity,
                                   sum(case when side = 'SELL' then quantity - filled_quantity else 0 end) as open_sell_quantity
                              from stock_order %s
                             where account_id in (:accountIds)
                               and market_type = 'ORDER_BOOK'
                               and status in ('PENDING', 'PARTIALLY_FILLED')
                             group by account_id
                            union all
                            select account_id,
                                   sum(subscribed_cash_amount) as reserved_buy_cash,
                                   0 as open_order_count,
                                   0 as open_buy_order_count,
                                   0 as open_sell_order_count,
                                   0 as open_buy_quantity,
                                   0 as open_sell_quantity
                              from stock_corporate_action_entitlement
                             where account_id in (:accountIds)
                               and status = 'SUBSCRIBED'
                             group by account_id
                      ) reserved_assets
                     group by account_id
                    """;

    static final String LAST_ORDER_LOOKUP_SQL = """
                    select cast(:accountId%d as %s) as account_id,
                           (
                               select created_at
                                 from stock_order %s
                                where account_id = :accountId%d
                                  and market_type = 'ORDER_BOOK'
                                  and created_at >= :activityStart
                                  and created_at <= :activityEnd
                                order by created_at desc
                                limit 1
                           ) as last_order_at
                    """;

    static final String LAST_ORDER_LOOKUP_ALL_SQL = """
                    select cast(:accountId%d as %s) as account_id,
                           (
                               select created_at
                                 from stock_order %s
                                where account_id = :accountId%d
                                  and market_type = 'ORDER_BOOK'
                                  and created_at <= :activityEnd
                                order by created_at desc
                                limit 1
                           ) as last_order_at
                    """;

    static final String EXECUTION_AGGREGATE_SQL = """
                    select account_id,
                           sum(case when simulation_trade_date = :todayDate then execution_count else 0 end) as today_execution_count,
                           sum(case when simulation_trade_date = :todayDate then buy_quantity else 0 end) as today_buy_quantity,
                           sum(case when simulation_trade_date = :todayDate then sell_quantity else 0 end) as today_sell_quantity,
                           sum(case when simulation_trade_date = :todayDate then gross_amount else 0 end) as today_gross_amount,
                           max(case
                                   when last_executed_at >= :activityStart
                                    and last_executed_at <= :activityEnd then last_executed_at
                               end) as last_execution_at
                     from stock_execution_account_day_summary
                     where account_id in (:accountIds)
                       and simulation_trade_date >= :activityStartDate
                       and simulation_trade_date <= :activityEndDate
                     group by account_id
                    """;

    static final String EXECUTION_AGGREGATE_ALL_SQL = """
                    select account_id,
                           sum(case when simulation_trade_date = :todayDate then execution_count else 0 end) as today_execution_count,
                           sum(case when simulation_trade_date = :todayDate then buy_quantity else 0 end) as today_buy_quantity,
                           sum(case when simulation_trade_date = :todayDate then sell_quantity else 0 end) as today_sell_quantity,
                           sum(case when simulation_trade_date = :todayDate then gross_amount else 0 end) as today_gross_amount,
                           max(case when last_executed_at <= :activityEnd then last_executed_at end) as last_execution_at
                      from stock_execution_account_day_summary
                     where account_id in (:accountIds)
                       and simulation_trade_date <= :activityEndDate
                     group by account_id
                    """;

    private final JdbcClient jdbcClient;
    private final String accountIdCastType;
    private final String openOrderIndexHint;
    private final String orderIndexHint;

    AutoParticipantAggregateQuerySupport(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = jdbcClient;
        boolean mysql = isMysql(jdbcTemplate);
        this.accountIdCastType = mysql ? "signed" : "bigint";
        this.openOrderIndexHint = mysql ? "force index (idx_stock_order_market_status_account_time)" : "";
        this.orderIndexHint = mysql ? "force index (idx_stock_order_account_market_created)" : "";
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
        jdbcClient.sql(OPEN_ORDER_AGGREGATE_SQL.formatted(openOrderIndexHint))
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
        var statement = jdbcClient.sql(latestLookupSql(
                        accountIds.size(),
                        activityWindow.all() ? LAST_ORDER_LOOKUP_ALL_SQL : LAST_ORDER_LOOKUP_SQL,
                        orderIndexHint
                ))
                .param("activityEnd", activityWindow.end());
        for (int index = 0; index < accountIds.size(); index++) {
            statement = statement.param("accountId" + index, accountIds.get(index));
        }
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
            if (target != null && row.lastOrderAt() != null) {
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
                                       when flow_type = 'WITHDRAW' and reason <> 'CAPITAL_INCREASE_SUBSCRIPTION' then -amount
                                       else 0
                                   end) as net_cash_flow
                          from stock_account_cash_flow
                         where account_id in (:accountIds)
                           and ((flow_type = 'WITHDRAW' and reason <> 'CAPITAL_INCREASE_SUBSCRIPTION')
                               or (flow_type = 'DEPOSIT' and reason <> 'DIVIDEND_PAYMENT'))
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
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                        activityWindow.all() ? EXECUTION_AGGREGATE_ALL_SQL : EXECUTION_AGGREGATE_SQL
                )
                .param("accountIds", accountIds)
                .param("todayDate", todayStart.toLocalDate())
                .param("activityEnd", activityWindow.end())
                .param("activityEndDate", activityWindow.end().toLocalDate());
        if (!activityWindow.all()) {
            statement = statement
                    .param("activityStart", activityWindow.start())
                    .param("activityStartDate", activityWindow.start().toLocalDate());
        }
        statement
                .query(rs -> {
                    T target = targetByAccountId.get(rs.getLong("account_id"));
                    if (target != null) {
                        target.addTodayExecutionSummary(
                                rs.getLong("today_execution_count"),
                                rs.getLong("today_buy_quantity"),
                                rs.getLong("today_sell_quantity"),
                                AutoParticipantQuerySupport.zeroIfNull(rs.getBigDecimal("today_gross_amount"))
                        );
                        LocalDateTime lastExecutionAt = rs.getObject("last_execution_at", LocalDateTime.class);
                        if (lastExecutionAt != null) {
                            target.recordLastExecutionAt(lastExecutionAt);
                        }
                    }
                });
    }

    static LocalDateTime max(LocalDateTime currentValue, LocalDateTime nextValue) {
        if (nextValue == null || currentValue != null && !nextValue.isAfter(currentValue)) {
            return currentValue;
        }
        return nextValue;
    }

    private String latestLookupSql(int accountCount, String template, String indexHint) {
        return IntStream.range(0, accountCount)
                .mapToObj(index -> template.formatted(index, accountIdCastType, indexHint, index))
                .collect(Collectors.joining("\nunion all\n"));
    }

    private boolean isMysql(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            return false;
        }
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
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
