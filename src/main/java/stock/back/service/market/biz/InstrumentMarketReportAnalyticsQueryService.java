package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.market.vo.InstrumentDailyMarketSnapshotResponse;
import stock.back.service.market.vo.InstrumentMarketAnalyticsResponse;
import web.common.core.simulation.SimulationClockSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class InstrumentMarketReportAnalyticsQueryService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int HISTORY_LIMIT = 61;
    private static final int EVENT_PRICE_MAX_DISTANCE_DAYS = 7;
    private static final List<String> FLOW_CATEGORIES = List.of(
            "MANUAL_PARTICIPANT",
            "AUTO_PARTICIPANT",
            "LISTING_UNDERWRITER"
    );
    private final JdbcClient jdbcClient;
    private final MeterRegistry meterRegistry;
    private final String executionFlowIndexHint;
    private final String executionActivityIndexHint;

    public InstrumentMarketReportAnalyticsQueryService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.meterRegistry = meterRegistry;
        boolean mysql = isMySql(jdbcTemplate);
        this.executionFlowIndexHint = mysql && hasIndex(jdbcTemplate, "stock_execution", "idx_stock_execution_market_report_flow")
                ? " force index (idx_stock_execution_market_report_flow)"
                : mysql ? " force index (idx_stock_execution_source_symbol_time)" : "";
        this.executionActivityIndexHint = mysql && hasIndex(jdbcTemplate, "stock_execution", "idx_stock_execution_candle")
                ? " force index (idx_stock_execution_candle)"
                : mysql ? " force index (idx_stock_execution_source_symbol_time)" : "";
    }

    @Transactional(readOnly = true)
    public InstrumentMarketAnalyticsResponse getAnalytics(
            String symbol,
            Long closeRunId,
            long issuedShares,
            long tradableShares,
            BigDecimal closePrice,
            LocalDateTime priceTime,
            String priceProvider,
            InstrumentDailyMarketSnapshotResponse daily,
            LocalDate reportDate,
            SimulationClockSnapshot clock
    ) {
        Timer.Sample totalSample = Timer.start(meterRegistry);
        try {
            return getAnalyticsTimed(
                    symbol,
                    closeRunId,
                    issuedShares,
                    tradableShares,
                    closePrice,
                    priceTime,
                    priceProvider,
                    daily,
                    reportDate,
                    clock
            );
        } finally {
            totalSample.stop(reportTimer("total"));
        }
    }

    private InstrumentMarketAnalyticsResponse getAnalyticsTimed(
            String symbol,
            Long closeRunId,
            long issuedShares,
            long tradableShares,
            BigDecimal closePrice,
            LocalDateTime priceTime,
            String priceProvider,
            InstrumentDailyMarketSnapshotResponse daily,
            LocalDate reportDate,
            SimulationClockSnapshot clock
    ) {
        HistoryData historyData = timed("history", () -> loadHistory(symbol, reportDate));
        List<HistoryRow> history = markReportDate(historyData.rows(), reportDate);
        LocalDate effectiveReportDate = reportDate == null ? clock.simulationDate() : reportDate;
        LocalDate fiveDayStart = tradingWindowStart(history, 5, effectiveReportDate);
        LocalDate twentyDayStart = tradingWindowStart(history, 20, effectiveReportDate);
        LocalDate volatilityWindowStart = tradingWindowStart(history, 21, effectiveReportDate);
        LocalDateTime reportEndExclusive = effectiveReportDate.plusDays(1).atStartOfDay();
        BigDecimal reportClosePrice = history.isEmpty() ? closePrice : history.getLast().closePrice();
        InstrumentMarketAnalyticsResponse.Performance performance = timed("performance", () -> buildPerformance(
                symbol,
                historyData.totalTradingDays(),
                history,
                reportClosePrice,
                daily,
                reportEndExclusive
        ));
        InstrumentMarketAnalyticsResponse.TradingActivity tradingActivity = timed("trading-activity", () -> buildTradingActivity(
                symbol,
                reportDate == null ? null : twentyDayStart.atStartOfDay(),
                reportDate == null ? null : reportEndExclusive
        ));
        InstrumentMarketAnalyticsResponse.InvestorFlow investorFlow = timed("investor-flow", () -> loadInvestorFlow(
                symbol,
                reportDate,
                fiveDayStart,
                twentyDayStart,
                reportEndExclusive,
                history
        ));
        InstrumentMarketAnalyticsResponse.Ownership ownership = timed("ownership", () -> loadOwnership(
                symbol,
                closeRunId,
                issuedShares,
                tradableShares,
                history
        ));
        InstrumentMarketAnalyticsResponse.CorporateActions corporateActions = timed("corporate-actions", () -> loadCorporateActions(
                symbol,
                issuedShares,
                history,
                reportDate == null ? null : reportEndExclusive
        ));
        InstrumentMarketAnalyticsResponse.Rankings rankings = reportDate == null
                ? emptyRankings()
                : timed("rankings", () -> loadRankings(symbol, effectiveReportDate, volatilityWindowStart));
        InstrumentMarketAnalyticsResponse.DataQuality dataQuality = buildDataQuality(
                priceTime,
                priceProvider,
                daily,
                historyData,
                reportDate,
                clock
        );
        return new InstrumentMarketAnalyticsResponse(
                performance,
                tradingActivity,
                investorFlow,
                ownership,
                corporateActions,
                rankings,
                dataQuality
        );
    }

    private <T> T timed(String section, Supplier<T> action) {
        return reportTimer(section).record(action);
    }

    private Timer reportTimer(String section) {
        return Timer.builder("stock.market.report.query.duration")
                .description("Instrument market report query and aggregation duration")
                .tag("section", section)
                .register(meterRegistry);
    }

    private HistoryData loadHistory(String symbol, LocalDate reportDate) {
        if (reportDate == null) {
            return new HistoryData(List.of(), 0, null, null);
        }
        List<HistoryQueryRow> queryRows = jdbcClient.sql(
                        """
                        select simulation_trade_date,
                               close_price,
                               execution_quantity,
                               turnover_amount,
                               issued_shares,
                               tradable_shares,
                               total_trading_days,
                               history_start_date,
                               history_end_date
                          from (
                                select simulation_trade_date,
                                       close_price,
                                       execution_quantity,
                                       turnover_amount,
                                       issued_shares,
                                       tradable_shares,
                                       close_run_id,
                                       count(*) over () as total_trading_days,
                                       min(simulation_trade_date) over () as history_start_date,
                                       max(simulation_trade_date) over () as history_end_date
                                  from (
                                        select snapshot.*,
                                               row_number() over (
                                                   partition by simulation_trade_date
                                                   order by close_run_id desc, id desc
                                               ) as date_rank
                                          from stock_order_book_daily_snapshot snapshot
                                          join stock_market_close_run close_run
                                            on close_run.id = snapshot.close_run_id
                                           and close_run.symbol is null
                                           and close_run.status = 'COMPLETED'
                                         where snapshot.symbol = ?
                                           and snapshot.simulation_trade_date <= ?
                                  ) latest_by_date
                                 where date_rank = 1
                                 order by simulation_trade_date desc
                                 limit ?
                          ) recent_history
                         order by simulation_trade_date asc, close_run_id asc
                        """
                )
                .params(symbol, reportDate, HISTORY_LIMIT)
                .query((rs, rowNum) -> new HistoryQueryRow(
                        new HistoryRow(
                                rs.getObject("simulation_trade_date", LocalDate.class),
                                rs.getBigDecimal("close_price"),
                                rs.getLong("execution_quantity") / 2L,
                                rs.getBigDecimal("turnover_amount").divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP),
                                rs.getLong("issued_shares"),
                                rs.getLong("tradable_shares"),
                                false
                        ),
                        rs.getInt("total_trading_days"),
                        rs.getObject("history_start_date", LocalDate.class),
                        rs.getObject("history_end_date", LocalDate.class)
                ))
                .list();
        if (queryRows.isEmpty()) {
            return new HistoryData(List.of(), 0, null, null);
        }
        HistoryQueryRow metadata = queryRows.getFirst();
        return new HistoryData(
                queryRows.stream().map(HistoryQueryRow::row).toList(),
                metadata.totalTradingDays(),
                metadata.historyStartDate(),
                metadata.historyEndDate()
        );
    }

    private List<HistoryRow> markReportDate(
            List<HistoryRow> historyRows,
            LocalDate reportDate
    ) {
        List<HistoryRow> marked = new ArrayList<>(historyRows.size());
        for (HistoryRow row : historyRows) {
            marked.add(new HistoryRow(
                    row.tradeDate(),
                    row.closePrice(),
                    row.volume(),
                    row.turnover(),
                    row.issuedShares(),
                    row.tradableShares(),
                    row.tradeDate().equals(reportDate)
            ));
        }
        return marked;
    }

    private InstrumentMarketAnalyticsResponse.Performance buildPerformance(
            String symbol,
            int completedTradingDays,
            List<HistoryRow> history,
            BigDecimal closePrice,
            InstrumentDailyMarketSnapshotResponse daily,
            LocalDateTime reportEndExclusive
    ) {
        LocalDate reportDate = reportEndExclusive.toLocalDate().minusDays(1);
        LocalDate twentyDayStart = tradingWindowStart(history, 20, reportDate);
        PriceRange priceRange = history.isEmpty()
                ? new PriceRange(null, null)
                : loadPriceRange(symbol, twentyDayStart.atStartOfDay(), reportEndExclusive);
        List<BigDecimal> closes = history.stream().map(HistoryRow::closePrice).toList();
        List<HistoryRow> completedVolumeRows = history.stream()
                .filter(row -> !row.reportDate())
                .toList();
        List<HistoryRow> averageVolumeRows = tail(completedVolumeRows, 20);
        BigDecimal averageVolume = averageVolumeRows.isEmpty()
                ? null
                : BigDecimal.valueOf(averageVolumeRows.stream().mapToLong(HistoryRow::volume).sum())
                        .divide(BigDecimal.valueOf(averageVolumeRows.size()), 4, RoundingMode.HALF_UP);
        BigDecimal averageTurnover = averageVolumeRows.isEmpty()
                ? null
                : averageVolumeRows.stream()
                        .map(HistoryRow::turnover)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(averageVolumeRows.size()), 4, RoundingMode.HALF_UP);
        BigDecimal averageTurnoverRate = averageVolumeRows.isEmpty()
                ? null
                : averageVolumeRows.stream()
                        .map(row -> rate(BigDecimal.valueOf(row.volume()), BigDecimal.valueOf(row.tradableShares())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(averageVolumeRows.size()), 4, RoundingMode.HALF_UP);
        BigDecimal high = priceRange.highPrice() == null
                ? tail(history, 20).stream().map(HistoryRow::closePrice).max(BigDecimal::compareTo).orElse(closePrice)
                : priceRange.highPrice();
        BigDecimal low = priceRange.lowPrice() == null
                ? tail(history, 20).stream().map(HistoryRow::closePrice).min(BigDecimal::compareTo).orElse(closePrice)
                : priceRange.lowPrice();
        int[] streaks = calculateStreaks(closes);
        return new InstrumentMarketAnalyticsResponse.Performance(
                completedTradingDays,
                periodReturn(closes, 5),
                periodReturn(closes, 20),
                periodReturn(closes, 60),
                high,
                low,
                rate(closePrice.subtract(high), high),
                calculateDailyVolatility(tail(closes, 21)),
                averageVolume,
                averageVolume == null || averageVolume.signum() <= 0
                        ? null
                        : BigDecimal.valueOf(daily.volume()).multiply(ONE_HUNDRED)
                                .divide(averageVolume, 4, RoundingMode.HALF_UP),
                averageTurnover,
                averageTurnover == null || averageTurnover.signum() <= 0
                        ? null
                        : daily.turnover().multiply(ONE_HUNDRED)
                                .divide(averageTurnover, 4, RoundingMode.HALF_UP),
                averageTurnoverRate,
                trend(closes, 20),
                streaks[0],
                streaks[1],
                history.stream().map(HistoryRow::toResponse).toList()
        );
    }

    private PriceRange loadPriceRange(String symbol, LocalDateTime start, LocalDateTime end) {
        return jdbcClient.sql(
                        """
                        select max(price) as high_price,
                               min(price) as low_price
                          from stock_execution
                         where symbol = ?
                           and source = 'INTERNAL_ORDER_BOOK'
                           and side = 'BUY'
                           and executed_at >= ?
                           and executed_at < ?
                        """
                )
                .params(symbol, start, end)
                .query((rs, rowNum) -> new PriceRange(
                        rs.getBigDecimal("high_price"),
                        rs.getBigDecimal("low_price")
                ))
                .single();
    }

    private InstrumentMarketAnalyticsResponse.TradingActivity buildTradingActivity(
            String symbol,
            LocalDateTime dayStart,
            LocalDateTime dayEnd
    ) {
        TradingActivityStats stats = dayStart == null || dayEnd == null
                ? emptyTradingActivityStats()
                : loadTradingActivityStats(symbol, dayStart, dayEnd);
        return new InstrumentMarketAnalyticsResponse.TradingActivity(
                stats.executionCount(),
                stats.executionQuantity(),
                stats.averageExecutionQuantity(),
                stats.averageSecondsBetweenTrades()
        );
    }

    private TradingActivityStats emptyTradingActivityStats() {
        return new TradingActivityStats(
                0L,
                0L,
                null,
                null
        );
    }

    private TradingActivityStats loadTradingActivityStats(
            String symbol,
            LocalDateTime dayStart,
            LocalDateTime dayEnd
    ) {
        ExecutionStatistics executionStats = jdbcClient.sql(
                        """
                        select count(*) as execution_count,
                               coalesce(sum(quantity), 0) as execution_quantity,
                               min(executed_at) as first_executed_at,
                               max(executed_at) as last_executed_at
                          from stock_execution%s
                         where source = 'INTERNAL_ORDER_BOOK'
                           and symbol = ?
                           and side = 'BUY'
                           and executed_at >= ?
                           and executed_at < ?
                        """.formatted(executionActivityIndexHint)
                )
                .params(symbol, dayStart, dayEnd)
                .query((rs, rowNum) -> new ExecutionStatistics(
                        rs.getLong("execution_count"),
                        rs.getLong("execution_quantity"),
                        MarketQuerySupport.toDateTime(rs.getTimestamp("first_executed_at")),
                        MarketQuerySupport.toDateTime(rs.getTimestamp("last_executed_at"))
                ))
                .single();
        BigDecimal averageSeconds = executionStats.executionCount() <= 1
                || executionStats.firstExecutedAt() == null
                || executionStats.lastExecutedAt() == null
                ? null
                : BigDecimal.valueOf(Duration.between(
                                executionStats.firstExecutedAt(),
                                executionStats.lastExecutedAt()
                        ).toMillis())
                        .divide(BigDecimal.valueOf(1_000L * (executionStats.executionCount() - 1L)), 4, RoundingMode.HALF_UP);
        return new TradingActivityStats(
                executionStats.executionCount(),
                executionStats.executionQuantity(),
                average(executionStats.executionQuantity(), executionStats.executionCount()),
                averageSeconds
        );
    }

    private InstrumentMarketAnalyticsResponse.InvestorFlow loadInvestorFlow(
            String symbol,
            LocalDate reportDate,
            LocalDate fiveDayStart,
            LocalDate twentyDayStart,
            LocalDateTime endExclusive,
            List<HistoryRow> history
    ) {
        if (reportDate == null) {
            return new InstrumentMarketAnalyticsResponse.InvestorFlow(
                    List.of(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            );
        }
        List<DailyCategoryFlowRow> dailyRows = jdbcClient.sql(
                        """
                        with account_daily_flow as (
                            select date(e.executed_at) as trade_date,
                                   e.account_id,
                                   sum(case when e.side = 'BUY' then e.quantity else 0 end) as buy_quantity,
                                   sum(case when e.side = 'SELL' then e.quantity else 0 end) as sell_quantity,
                                   sum(case when e.side = 'BUY' then e.gross_amount else 0 end) as buy_amount,
                                   sum(case when e.side = 'SELL' then e.gross_amount else 0 end) as sell_amount,
                                   sum(case when e.side = 'BUY' then -e.net_amount else e.net_amount end) as net_cash_flow,
                                   sum(e.gross_amount) as execution_amount
                              from stock_execution e%s
                             where e.symbol = :symbol
                               and e.source = 'INTERNAL_ORDER_BOOK'
                               and e.executed_at >= :start
                               and e.executed_at < :end
                             group by date(e.executed_at), e.account_id
                        )
                        select flow.trade_date,
                               case
                                   when listing_config.user_key is not null then 'LISTING_UNDERWRITER'
                                   when participant.user_key is not null then 'AUTO_PARTICIPANT'
                                   else 'MANUAL_PARTICIPANT'
                               end as participant_category,
                               sum(flow.buy_quantity) as buy_quantity,
                               sum(flow.sell_quantity) as sell_quantity,
                               sum(flow.buy_amount) as buy_amount,
                               sum(flow.sell_amount) as sell_amount,
                               sum(flow.net_cash_flow) as net_cash_flow,
                               sum(flow.execution_amount) as execution_amount
                          from account_daily_flow flow
                          join stock_account account on account.id = flow.account_id
                          left join stock_listing_auto_account_config listing_config
                            on listing_config.user_key = account.user_key
                           and listing_config.symbol = :symbol
                          left join stock_auto_participant participant
                            on participant.user_key = account.user_key
                         group by flow.trade_date, participant_category
                        """.formatted(executionFlowIndexHint)
                )
                .param("symbol", symbol)
                .param("start", twentyDayStart.atStartOfDay())
                .param("end", endExclusive)
                .query((rs, rowNum) -> new DailyCategoryFlowRow(
                        rs.getObject("trade_date", LocalDate.class),
                        rs.getString("participant_category"),
                        rs.getLong("buy_quantity"),
                        rs.getLong("sell_quantity"),
                        rs.getBigDecimal("buy_amount"),
                        rs.getBigDecimal("sell_amount"),
                        rs.getBigDecimal("net_cash_flow"),
                        rs.getBigDecimal("execution_amount")
                ))
                .list();
        List<InstrumentMarketAnalyticsResponse.FlowWindow> windows = List.of(
                flowWindow("1D", tradingDays(history, reportDate, reportDate), reportDate, reportDate, dailyRows),
                flowWindow("5D", tradingDays(history, fiveDayStart, reportDate), fiveDayStart, reportDate, dailyRows),
                flowWindow("20D", tradingDays(history, twentyDayStart, reportDate), twentyDayStart, reportDate, dailyRows)
        );
        AccountConcentration concentration = loadAccountConcentration(
                symbol,
                twentyDayStart.atStartOfDay(),
                endExclusive
        );
        InstrumentMarketAnalyticsResponse.FlowWindow latestTradingDay = windows.getFirst();
        BigDecimal autoShare = latestTradingDay.categories().stream()
                .filter(category -> "AUTO_PARTICIPANT".equals(category.category()))
                .map(InstrumentMarketAnalyticsResponse.CategoryFlow::executionShareRate)
                .findFirst()
                .orElse(BigDecimal.ZERO);
        return new InstrumentMarketAnalyticsResponse.InvestorFlow(
                windows,
                autoShare,
                concentration.topAccountShareRate()
        );
    }

    private int tradingDays(List<HistoryRow> history, LocalDate startDate, LocalDate endDate) {
        return (int) history.stream()
                .filter(row -> !row.tradeDate().isBefore(startDate) && !row.tradeDate().isAfter(endDate))
                .count();
    }

    private InstrumentMarketAnalyticsResponse.FlowWindow flowWindow(
            String window,
            int tradingDays,
            LocalDate startDate,
            LocalDate endDate,
            List<DailyCategoryFlowRow> rows
    ) {
        Map<String, MutableCategoryFlow> aggregates = new LinkedHashMap<>();
        FLOW_CATEGORIES.forEach(category -> aggregates.put(category, new MutableCategoryFlow()));
        for (DailyCategoryFlowRow row : rows) {
            if (row.tradeDate().isBefore(startDate) || row.tradeDate().isAfter(endDate)) {
                continue;
            }
            aggregates.computeIfAbsent(row.category(), ignored -> new MutableCategoryFlow()).add(row);
        }
        BigDecimal totalExecutionAmount = aggregates.values().stream()
                .map(MutableCategoryFlow::executionAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<InstrumentMarketAnalyticsResponse.CategoryFlow> categories = aggregates.entrySet().stream()
                .map(entry -> entry.getValue().toResponse(entry.getKey(), totalExecutionAmount))
                .toList();
        return new InstrumentMarketAnalyticsResponse.FlowWindow(
                window,
                tradingDays,
                startDate,
                endDate,
                categories
        );
    }

    private AccountConcentration loadAccountConcentration(String symbol, LocalDateTime start, LocalDateTime end) {
        return jdbcClient.sql(
                        """
                        select coalesce(max(account_amount), 0) as top_account_amount,
                               coalesce(sum(account_amount), 0) as total_amount
                          from (
                                select account_id,
                                       sum(gross_amount) as account_amount
                                  from stock_execution
                                 where symbol = ?
                                   and source = 'INTERNAL_ORDER_BOOK'
                                   and executed_at >= ?
                                   and executed_at < ?
                                 group by account_id
                          ) account_flow
                        """
                )
                .params(symbol, start, end)
                .query((rs, rowNum) -> new AccountConcentration(
                        rate(rs.getBigDecimal("top_account_amount"), rs.getBigDecimal("total_amount"))
                ))
                .single();
    }

    private InstrumentMarketAnalyticsResponse.Ownership loadOwnership(
            String symbol,
            Long closeRunId,
            long issuedShares,
            long tradableShares,
            List<HistoryRow> history
    ) {
        List<Long> holdingQuantities = closeRunId == null
                ? List.of()
                : jdbcClient.sql(
                                """
                                select quantity
                                  from stock_holding_snapshot
                                 where close_run_id = ?
                                   and symbol = ?
                                   and quantity > 0
                                 order by quantity desc, account_id asc
                                """
                        )
                        .params(closeRunId, symbol)
                        .query(Long.class)
                        .list();
        long accountedQuantity = holdingQuantities.stream().mapToLong(Long::longValue).sum();
        long topHolderQuantity = holdingQuantities.stream().findFirst().orElse(0L);
        long topFiveQuantity = holdingQuantities.stream().limit(5).mapToLong(Long::longValue).sum();
        List<InstrumentMarketAnalyticsResponse.ShareHistoryPoint> shareHistory = shareHistory(history);
        long issuedChange = history.isEmpty() ? 0L : issuedShares - history.getFirst().issuedShares();
        long tradableChange = history.isEmpty() ? 0L : tradableShares - history.getFirst().tradableShares();
        return new InstrumentMarketAnalyticsResponse.Ownership(
                holdingQuantities.size(),
                accountedQuantity,
                rate(BigDecimal.valueOf(accountedQuantity), BigDecimal.valueOf(issuedShares)),
                topHolderQuantity,
                rate(BigDecimal.valueOf(topHolderQuantity), BigDecimal.valueOf(issuedShares)),
                topFiveQuantity,
                rate(BigDecimal.valueOf(topFiveQuantity), BigDecimal.valueOf(issuedShares)),
                issuedChange,
                tradableChange,
                shareHistory
        );
    }

    private List<InstrumentMarketAnalyticsResponse.ShareHistoryPoint> shareHistory(List<HistoryRow> history) {
        if (history.isEmpty()) {
            return List.of();
        }
        List<InstrumentMarketAnalyticsResponse.ShareHistoryPoint> result = new ArrayList<>();
        HistoryRow previous = null;
        for (int index = 0; index < history.size(); index++) {
            HistoryRow current = history.get(index);
            long issuedChange = previous == null ? 0L : current.issuedShares() - previous.issuedShares();
            long tradableChange = previous == null ? 0L : current.tradableShares() - previous.tradableShares();
            boolean boundary = index == 0 || index == history.size() - 1;
            if (boundary || issuedChange != 0L || tradableChange != 0L) {
                result.add(new InstrumentMarketAnalyticsResponse.ShareHistoryPoint(
                        current.tradeDate(),
                        current.issuedShares(),
                        current.tradableShares(),
                        issuedChange,
                        tradableChange
                ));
            }
            previous = current;
        }
        return result;
    }

    private InstrumentMarketAnalyticsResponse.CorporateActions loadCorporateActions(
            String symbol,
            long currentIssuedShares,
            List<HistoryRow> history,
            LocalDateTime reportEndExclusive
    ) {
        if (reportEndExclusive == null) {
            return new InstrumentMarketAnalyticsResponse.CorporateActions(
                    0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, List.of()
            );
        }
        List<CorporateActionRow> rows = jdbcClient.sql(
                        """
                        select action.id,
                               action.action_type,
                               action.applied_at,
                               action.paid_at,
                               action.listed_at,
                               action.offering_type,
                               action.share_quantity,
                               action.issue_price,
                               action.base_price,
                               action.theoretical_ex_rights_price,
                               action.dividend_amount,
                               action.split_from,
                               action.split_to,
                               action.ex_rights_date,
                               action.subscription_start_date,
                               action.subscription_end_date,
                               action.payment_date,
                               action.listing_date,
                               action.delisting_date,
                               action.description,
                               action.created_at
                          from stock_corporate_action action
                         where action.symbol = ?
                           and action.created_at < ?
                         order by action.created_at desc, action.id desc
                        """
                )
                .params(symbol, reportEndExclusive)
                .query((rs, rowNum) -> toCorporateActionRow(rs, reportEndExclusive))
                .list();
        BigDecimal cumulativeDividendCash = jdbcClient.sql(
                        """
                        select coalesce(sum(entitlement.cash_amount), 0) as paid_dividend_cash
                          from stock_corporate_action_entitlement entitlement
                          join stock_corporate_action action on action.id = entitlement.action_id
                         where action.symbol = ?
                           and action.action_type = 'CASH_DIVIDEND'
                           and entitlement.paid_at < ?
                        """
                )
                .params(symbol, reportEndExclusive)
                .query(BigDecimal.class)
                .single();
        long completedCount = rows.stream().filter(this::isCorporateActionCompleted).count();
        BigDecimal cumulativeDividendPerShare = rows.stream()
                .filter(row -> row.actionType() == StockCorporateActionType.CASH_DIVIDEND)
                .filter(row -> row.status() == StockCorporateActionStatus.PAID)
                .map(CorporateActionRow::dividendAmount)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InstrumentMarketAnalyticsResponse.CorporateActions(
                rows.size() - completedCount,
                completedCount,
                cumulativeDividendPerShare,
                cumulativeDividendCash,
                rows.stream()
                        .map(row -> toCorporateActionMetric(row, currentIssuedShares, history))
                        .toList()
        );
    }

    private CorporateActionRow toCorporateActionRow(ResultSet rs, LocalDateTime reportEndExclusive) throws SQLException {
        String offeringType = rs.getString("offering_type");
        StockCorporateActionType actionType = StockCorporateActionType.valueOf(rs.getString("action_type"));
        return new CorporateActionRow(
                rs.getLong("id"),
                actionType,
                corporateActionStatusAt(
                        actionType,
                        rs.getObject("applied_at", LocalDateTime.class),
                        rs.getObject("paid_at", LocalDateTime.class),
                        rs.getObject("listed_at", LocalDateTime.class),
                        reportEndExclusive
                ),
                offeringType == null ? null : StockCapitalIncreaseOfferingType.valueOf(offeringType),
                rs.getObject("share_quantity", Long.class),
                rs.getBigDecimal("issue_price"),
                rs.getBigDecimal("base_price"),
                rs.getBigDecimal("theoretical_ex_rights_price"),
                rs.getBigDecimal("dividend_amount"),
                rs.getObject("split_from", Integer.class),
                rs.getObject("split_to", Integer.class),
                rs.getObject("ex_rights_date", LocalDate.class),
                rs.getObject("subscription_start_date", LocalDate.class),
                rs.getObject("subscription_end_date", LocalDate.class),
                rs.getObject("payment_date", LocalDate.class),
                rs.getObject("listing_date", LocalDate.class),
                rs.getObject("delisting_date", LocalDate.class),
                rs.getString("description"),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    static StockCorporateActionStatus corporateActionStatusAt(
            StockCorporateActionType actionType,
            LocalDateTime appliedAt,
            LocalDateTime paidAt,
            LocalDateTime listedAt,
            LocalDateTime reportEndExclusive
    ) {
        if (actionType == StockCorporateActionType.DELISTING
                && isBefore(appliedAt, reportEndExclusive)) {
            return StockCorporateActionStatus.DELISTED;
        }
        if (isBefore(listedAt, reportEndExclusive)) {
            return StockCorporateActionStatus.LISTED;
        }
        if (isBefore(paidAt, reportEndExclusive)) {
            return StockCorporateActionStatus.PAID;
        }
        if (isBefore(appliedAt, reportEndExclusive)) {
            return StockCorporateActionStatus.EX_RIGHTS_APPLIED;
        }
        return StockCorporateActionStatus.ANNOUNCED;
    }

    private static boolean isBefore(LocalDateTime value, LocalDateTime endExclusive) {
        return value != null && value.isBefore(endExclusive);
    }

    private InstrumentMarketAnalyticsResponse.CorporateActionMetric toCorporateActionMetric(
            CorporateActionRow row,
            long currentIssuedShares,
            List<HistoryRow> history
    ) {
        LocalDate anchorDate = firstNonNull(
                row.exRightsDate(),
                row.listingDate(),
                row.paymentDate(),
                row.createdAt().toLocalDate()
        );
        HistoryRow beforeCandidate = history.stream()
                .filter(point -> point.tradeDate().isBefore(anchorDate))
                .max(Comparator.comparing(HistoryRow::tradeDate))
                .orElse(null);
        HistoryRow afterCandidate = history.stream()
                .filter(point -> !point.tradeDate().isBefore(anchorDate))
                .min(Comparator.comparing(HistoryRow::tradeDate))
                .orElse(null);
        HistoryRow before = beforeCandidate != null
                && ChronoUnit.DAYS.between(beforeCandidate.tradeDate(), anchorDate) <= EVENT_PRICE_MAX_DISTANCE_DAYS
                ? beforeCandidate
                : null;
        HistoryRow after = afterCandidate != null
                && ChronoUnit.DAYS.between(anchorDate, afterCandidate.tradeDate()) <= EVENT_PRICE_MAX_DISTANCE_DAYS
                ? afterCandidate
                : null;
        Long shareQuantity = row.shareQuantity();
        Long preActionIssuedShares = before == null ? null : before.issuedShares();
        if (preActionIssuedShares == null
                && shareQuantity != null
                && isShareIssuePendingListing(row)) {
            preActionIssuedShares = currentIssuedShares;
        }
        BigDecimal newShareRate = shareQuantity == null || preActionIssuedShares == null
                ? null
                : rate(BigDecimal.valueOf(shareQuantity), BigDecimal.valueOf(preActionIssuedShares));
        BigDecimal dilutionRate = shareQuantity == null || preActionIssuedShares == null
                ? null
                : rate(
                        BigDecimal.valueOf(shareQuantity),
                        BigDecimal.valueOf(preActionIssuedShares).add(BigDecimal.valueOf(shareQuantity))
                );
        BigDecimal splitRatio = row.splitFrom() == null || row.splitTo() == null
                ? null
                : BigDecimal.valueOf(row.splitTo())
                        .divide(BigDecimal.valueOf(row.splitFrom()), 4, RoundingMode.HALF_UP);
        return new InstrumentMarketAnalyticsResponse.CorporateActionMetric(
                row.id(),
                row.actionType(),
                row.status(),
                row.offeringType(),
                shareQuantity == null ? 0L : shareQuantity,
                row.issuePrice(),
                row.basePrice(),
                row.theoreticalExRightsPrice(),
                row.issuePrice() == null || row.basePrice() == null
                        ? null
                        : rate(row.basePrice().subtract(row.issuePrice()), row.basePrice()),
                row.actionType() == StockCorporateActionType.INITIAL_ISSUE ? null : newShareRate,
                row.actionType() == StockCorporateActionType.INITIAL_ISSUE ? null : dilutionRate,
                row.dividendAmount(),
                row.dividendAmount() == null || row.basePrice() == null
                        ? null
                        : rate(row.dividendAmount(), row.basePrice()),
                splitRatio,
                row.exRightsDate(),
                row.subscriptionStartDate(),
                row.subscriptionEndDate(),
                row.paymentDate(),
                row.listingDate(),
                row.delistingDate(),
                before == null ? null : before.closePrice(),
                after == null ? null : after.closePrice(),
                before == null ? null : before.issuedShares(),
                after == null ? null : after.issuedShares(),
                before == null ? null : before.closePrice().multiply(BigDecimal.valueOf(before.issuedShares())),
                after == null ? null : after.closePrice().multiply(BigDecimal.valueOf(after.issuedShares())),
                row.description(),
                row.createdAt()
        );
    }

    private InstrumentMarketAnalyticsResponse.Rankings loadRankings(
            String selectedSymbol,
            LocalDate reportDate,
            LocalDate volatilityWindowStart
    ) {
        List<RankingCandidate> candidates = jdbcClient.sql(
                        """
                        with latest_daily as (
                             select symbol,
                                    name,
                                    enabled,
                                    issued_shares,
                                    tradable_shares,
                                    close_price,
                                    previous_close,
                                    execution_quantity,
                                    turnover_amount
                               from (
                                    select snapshot.*,
                                           row_number() over (
                                               partition by snapshot.symbol
                                               order by snapshot.close_run_id desc, snapshot.id desc
                                           ) as snapshot_rank
                                      from stock_order_book_daily_snapshot snapshot
                                      join stock_market_close_run close_run
                                        on close_run.id = snapshot.close_run_id
                                       and close_run.symbol is null
                                       and close_run.status = 'COMPLETED'
                                     where snapshot.simulation_trade_date = ?
                               ) ranked_snapshot
                              where snapshot_rank = 1
                        )
                        select symbol,
                               name,
                               issued_shares,
                               tradable_shares,
                               close_price,
                               previous_close,
                               execution_quantity / 2 as volume,
                               turnover_amount / 2 as turnover
                          from latest_daily
                         where enabled = true
                        """
                )
                .param(reportDate)
                .query((rs, rowNum) -> RankingCandidate.from(rs))
                .list();
        Map<String, List<BigDecimal>> historyBySymbol = new LinkedHashMap<>();
        jdbcClient.sql(
                        """
                        select symbol,
                               simulation_trade_date,
                               close_price
                          from (
                                select snapshot.symbol,
                                       snapshot.simulation_trade_date,
                                       snapshot.close_price,
                                       row_number() over (
                                           partition by snapshot.symbol, snapshot.simulation_trade_date
                                           order by snapshot.close_run_id desc, snapshot.id desc
                                       ) as date_rank
                                  from stock_order_book_daily_snapshot snapshot
                                  join stock_market_close_run close_run
                                    on close_run.id = snapshot.close_run_id
                                   and close_run.symbol is null
                                   and close_run.status = 'COMPLETED'
                                 where snapshot.simulation_trade_date >= ?
                                   and snapshot.simulation_trade_date <= ?
                          ) latest_by_date
                         where date_rank = 1
                         order by symbol asc, simulation_trade_date asc
                        """
                )
                .params(volatilityWindowStart, reportDate)
                .query((rs, rowNum) -> new SymbolCloseRow(
                        rs.getString("symbol"),
                        rs.getBigDecimal("close_price")
                ))
                .list()
                .forEach(row -> historyBySymbol.computeIfAbsent(row.symbol(), ignored -> new ArrayList<>()).add(row.closePrice()));
        candidates = candidates.stream()
                .map(candidate -> candidate.withVolatility(calculateDailyVolatility(
                        tail(historyBySymbol.getOrDefault(candidate.symbol(), List.of()), 21)
                )))
                .toList();
        RankingCandidate selected = candidates.stream()
                .filter(candidate -> candidate.symbol().equals(selectedSymbol))
                .findFirst()
                .orElseThrow();
        BigDecimal marketAverageReturn = candidates.stream()
                .map(RankingCandidate::changeRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, candidates.size())), 4, RoundingMode.HALF_UP);
        List<InstrumentMarketAnalyticsResponse.PeerInstrument> peers = candidates.stream()
                .filter(candidate -> !candidate.symbol().equals(selectedSymbol))
                .sorted(Comparator.comparing(candidate -> candidate.marketCapitalization().subtract(
                        selected.marketCapitalization()
                ).abs()))
                .limit(3)
                .map(RankingCandidate::toPeer)
                .toList();
        return new InstrumentMarketAnalyticsResponse.Rankings(
                candidates.size(),
                metricRank(candidates, selected, RankingCandidate::marketCapitalization, false),
                metricRank(candidates, selected, RankingCandidate::turnover, false),
                metricRank(candidates, selected, candidate -> BigDecimal.valueOf(candidate.volume()), false),
                metricRank(candidates, selected, RankingCandidate::changeRate, false),
                metricRank(candidates, selected, RankingCandidate::turnoverRate, false),
                metricRank(candidates, selected, RankingCandidate::volatility, false),
                marketAverageReturn,
                selected.changeRate().subtract(marketAverageReturn),
                peers
        );
    }

    private InstrumentMarketAnalyticsResponse.MetricRank metricRank(
            List<RankingCandidate> candidates,
            RankingCandidate selected,
            java.util.function.Function<RankingCandidate, BigDecimal> value,
            boolean lowerIsBetter
    ) {
        BigDecimal selectedValue = value.apply(selected);
        List<RankingCandidate> eligible = candidates.stream()
                .filter(candidate -> value.apply(candidate) != null)
                .toList();
        if (selectedValue == null) {
            return new InstrumentMarketAnalyticsResponse.MetricRank(
                    0,
                    eligible.size(),
                    null,
                    lowerIsBetter
            );
        }
        Comparator<RankingCandidate> comparator = Comparator.comparing(value);
        if (!lowerIsBetter) {
            comparator = comparator.reversed();
        }
        List<RankingCandidate> ranked = eligible.stream()
                .sorted(comparator.thenComparing(RankingCandidate::symbol))
                .toList();
        return new InstrumentMarketAnalyticsResponse.MetricRank(
                ranked.indexOf(selected) + 1,
                ranked.size(),
                selectedValue,
                lowerIsBetter
        );
    }

    private InstrumentMarketAnalyticsResponse.Rankings emptyRankings() {
        InstrumentMarketAnalyticsResponse.MetricRank emptyRank =
                new InstrumentMarketAnalyticsResponse.MetricRank(0, 0, null, false);
        return new InstrumentMarketAnalyticsResponse.Rankings(
                0,
                emptyRank,
                emptyRank,
                emptyRank,
                emptyRank,
                emptyRank,
                emptyRank,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()
        );
    }

    private InstrumentMarketAnalyticsResponse.DataQuality buildDataQuality(
            LocalDateTime priceTime,
            String priceProvider,
            InstrumentDailyMarketSnapshotResponse daily,
            HistoryData historyData,
            LocalDate reportDate,
            SimulationClockSnapshot clock
    ) {
        MarketCloseState closeState = loadMarketCloseState(reportDate);
        boolean hasReportDateTrades = daily.tradeCount() > 0;
        List<String> notes = new ArrayList<>();
        if (historyData.totalTradingDays() < 20) {
            notes.add("20거래일 미만의 일별 스냅샷만 보유하고 있습니다.");
        }
        if (reportDate == null) {
            notes.add("완료된 전체 장마감 종목 스냅샷이 없어 기간 보고서를 계산할 수 없습니다.");
        } else if (!hasReportDateTrades) {
            notes.add("보고서 기준 거래일의 체결이 없습니다.");
        }
        if (!closeState.reportDateCloseCompleted()) {
            notes.add("보고서 기준 거래일의 전체 마감 완료 기록이 없습니다.");
        }
        if (notes.isEmpty()) {
            notes.add("보고서 기준일의 가격·체결·20거래일 이상 이력·전체 마감 기록이 모두 확인됐습니다.");
        }
        String level = historyData.totalTradingDays() >= 20 && hasReportDateTrades && closeState.reportDateCloseCompleted()
                ? "FULL"
                : historyData.totalTradingDays() >= 5 && priceTime != null ? "PARTIAL" : "LIMITED";
        return new InstrumentMarketAnalyticsResponse.DataQuality(
                level,
                notes,
                List.of(
                        "장중 호가 이력이 없어 기간 평균 스프레드·호가 깊이·유효 스프레드는 산출하지 않습니다.",
                        "체결 시점 최우선 호가와 체결 주도 방향이 없어 가격 충격·체결강도는 산출하지 않습니다.",
                        "주문 취소율·완전체결률·체결 소요시간은 종목 성과가 아닌 주문 집행 품질이므로 별도 운영 보고서 대상으로 분리합니다.",
                        "보호예수와 기관·외국인 분류 원장이 없어 해당 비율과 투자자 수급은 산출하지 않습니다.",
                        "재무제표·공시·뉴스 원장이 없어 가치평가와 실적 전망은 포함하지 않습니다.",
                        "기업 이벤트 권리 상태의 시점별 변경 이력이 없어 기준일 당시 개별 계좌 진행률은 표시하지 않습니다."
                ),
                reportDate,
                clock.simulationDateTime(),
                priceTime,
                daily.lastExecutedAt(),
                priceProvider,
                "INTERNAL_ORDER_BOOK BUY-side ledger",
                historyData.totalTradingDays(),
                historyData.historyStartDate(),
                historyData.historyEndDate(),
                hasReportDateTrades,
                closeState.reportDateCloseCompleted(),
                closeState.latestCompletedDate(),
                closeState.latestCompletedAt()
        );
    }

    private boolean isCorporateActionCompleted(CorporateActionRow row) {
        return switch (row.actionType()) {
            case CASH_DIVIDEND -> row.status() == StockCorporateActionStatus.PAID;
            case DELISTING -> row.status() == StockCorporateActionStatus.DELISTED;
            case INITIAL_ISSUE, PAID_IN_CAPITAL_INCREASE, STOCK_SPLIT, BONUS_ISSUE, STOCK_DIVIDEND ->
                    row.status() == StockCorporateActionStatus.LISTED;
        };
    }

    private boolean isShareIssuePendingListing(CorporateActionRow row) {
        return switch (row.actionType()) {
            case PAID_IN_CAPITAL_INCREASE, BONUS_ISSUE, STOCK_DIVIDEND ->
                    row.status() != StockCorporateActionStatus.LISTED;
            case INITIAL_ISSUE, STOCK_SPLIT, CASH_DIVIDEND, DELISTING -> false;
        };
    }

    private MarketCloseState loadMarketCloseState(LocalDate reportDate) {
        if (reportDate == null) {
            return new MarketCloseState(false, null, null);
        }
        List<MarketCloseRow> rows = jdbcClient.sql(
                        """
                        select business_date,
                               completed_at
                          from stock_market_close_run
                         where symbol is null
                           and status = 'COMPLETED'
                           and business_date = ?
                         order by business_date desc, id desc
                         limit 1
                        """
                )
                .param(reportDate)
                .query((rs, rowNum) -> new MarketCloseRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("completed_at", LocalDateTime.class)
                ))
                .list();
        MarketCloseRow latest = rows.stream().findFirst().orElse(null);
        return new MarketCloseState(
                latest != null,
                latest == null ? null : latest.businessDate(),
                latest == null ? null : latest.completedAt()
        );
    }

    private boolean isMySql(JdbcTemplate jdbcTemplate) {
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    private boolean hasIndex(JdbcTemplate jdbcTemplate, String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = ?
                   and index_name = ?
                """,
                Integer.class,
                tableName,
                indexName
        );
        return count != null && count > 0;
    }

    private LocalDate tradingWindowStart(List<HistoryRow> history, int days, LocalDate fallback) {
        List<HistoryRow> window = tail(history, days);
        return window.isEmpty() ? fallback : window.getFirst().tradeDate();
    }

    private BigDecimal periodReturn(List<BigDecimal> closes, int period) {
        if (closes.size() <= period) {
            return null;
        }
        BigDecimal current = closes.getLast();
        BigDecimal base = closes.get(closes.size() - 1 - period);
        return rate(current.subtract(base), base);
    }

    static BigDecimal calculateDailyVolatility(List<BigDecimal> closes) {
        if (closes.size() < 2) {
            return null;
        }
        List<Double> returns = new ArrayList<>();
        for (int index = 1; index < closes.size(); index++) {
            BigDecimal previous = closes.get(index - 1);
            if (previous.signum() > 0) {
                returns.add(closes.get(index).subtract(previous)
                        .divide(previous, 10, RoundingMode.HALF_UP)
                        .multiply(ONE_HUNDRED)
                        .doubleValue());
            }
        }
        if (returns.size() < 2) {
            return null;
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double squaredDeviationSum = returns.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .sum();
        double variance = squaredDeviationSum / (returns.size() - 1D);
        return BigDecimal.valueOf(Math.sqrt(variance)).setScale(4, RoundingMode.HALF_UP);
    }

    private String trend(List<BigDecimal> closes, int period) {
        BigDecimal value = periodReturn(closes, period);
        if (value == null) {
            return "INSUFFICIENT_DATA";
        }
        if (value.signum() > 0) {
            return "UP";
        }
        if (value.signum() < 0) {
            return "DOWN";
        }
        return "FLAT";
    }

    private int[] calculateStreaks(List<BigDecimal> closes) {
        int up = 0;
        int down = 0;
        for (int index = closes.size() - 1; index > 0; index--) {
            int comparison = closes.get(index).compareTo(closes.get(index - 1));
            if (comparison > 0 && down == 0) {
                up++;
            } else if (comparison < 0 && up == 0) {
                down++;
            } else {
                break;
            }
        }
        return new int[]{up, down};
    }

    private BigDecimal average(long total, long count) {
        return count <= 0
                ? null
                : BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(ONE_HUNDRED).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static <T> List<T> tail(List<T> values, int limit) {
        if (values.size() <= limit) {
            return values;
        }
        return values.subList(values.size() - limit, values.size());
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        throw new IllegalArgumentException("At least one value is required");
    }

    private record HistoryData(
            List<HistoryRow> rows,
            int totalTradingDays,
            LocalDate historyStartDate,
            LocalDate historyEndDate
    ) {
    }

    private record HistoryQueryRow(
            HistoryRow row,
            int totalTradingDays,
            LocalDate historyStartDate,
            LocalDate historyEndDate
    ) {
    }

    private record HistoryRow(
            LocalDate tradeDate,
            BigDecimal closePrice,
            long volume,
            BigDecimal turnover,
            long issuedShares,
            long tradableShares,
            boolean reportDate
    ) {
        private InstrumentMarketAnalyticsResponse.DailyHistoryPoint toResponse() {
            return new InstrumentMarketAnalyticsResponse.DailyHistoryPoint(
                    tradeDate,
                    closePrice,
                    volume,
                    turnover,
                    issuedShares,
                    tradableShares,
                    reportDate
            );
        }
    }

    private record PriceRange(BigDecimal highPrice, BigDecimal lowPrice) {
    }

    private record ExecutionStatistics(
            long executionCount,
            long executionQuantity,
            LocalDateTime firstExecutedAt,
            LocalDateTime lastExecutedAt
    ) {
    }

    private record TradingActivityStats(
            long executionCount,
            long executionQuantity,
            BigDecimal averageExecutionQuantity,
            BigDecimal averageSecondsBetweenTrades
    ) {
    }

    private record DailyCategoryFlowRow(
            LocalDate tradeDate,
            String category,
            long buyQuantity,
            long sellQuantity,
            BigDecimal buyAmount,
            BigDecimal sellAmount,
            BigDecimal netCashFlow,
            BigDecimal executionAmount
    ) {
    }

    private static final class MutableCategoryFlow {
        private long buyQuantity;
        private long sellQuantity;
        private BigDecimal buyAmount = BigDecimal.ZERO;
        private BigDecimal sellAmount = BigDecimal.ZERO;
        private BigDecimal netCashFlow = BigDecimal.ZERO;
        private BigDecimal executionAmount = BigDecimal.ZERO;

        private void add(DailyCategoryFlowRow row) {
            buyQuantity += row.buyQuantity();
            sellQuantity += row.sellQuantity();
            buyAmount = buyAmount.add(row.buyAmount());
            sellAmount = sellAmount.add(row.sellAmount());
            netCashFlow = netCashFlow.add(row.netCashFlow());
            executionAmount = executionAmount.add(row.executionAmount());
        }

        private BigDecimal executionAmount() {
            return executionAmount;
        }

        private InstrumentMarketAnalyticsResponse.CategoryFlow toResponse(
                String category,
                BigDecimal totalExecutionAmount
        ) {
            return new InstrumentMarketAnalyticsResponse.CategoryFlow(
                    category,
                    buyQuantity,
                    sellQuantity,
                    buyQuantity - sellQuantity,
                    buyAmount,
                    sellAmount,
                    netCashFlow,
                    sellQuantity <= 0
                            ? null
                            : BigDecimal.valueOf(buyQuantity).multiply(ONE_HUNDRED)
                                    .divide(BigDecimal.valueOf(sellQuantity), 4, RoundingMode.HALF_UP),
                    rate(executionAmount, totalExecutionAmount)
            );
        }
    }

    private record AccountConcentration(BigDecimal topAccountShareRate) {
    }

    private record CorporateActionRow(
            long id,
            StockCorporateActionType actionType,
            StockCorporateActionStatus status,
            StockCapitalIncreaseOfferingType offeringType,
            Long shareQuantity,
            BigDecimal issuePrice,
            BigDecimal basePrice,
            BigDecimal theoreticalExRightsPrice,
            BigDecimal dividendAmount,
            Integer splitFrom,
            Integer splitTo,
            LocalDate exRightsDate,
            LocalDate subscriptionStartDate,
            LocalDate subscriptionEndDate,
            LocalDate paymentDate,
            LocalDate listingDate,
            LocalDate delistingDate,
            String description,
            LocalDateTime createdAt
    ) {
    }

    private record SymbolCloseRow(String symbol, BigDecimal closePrice) {
    }

    private record RankingCandidate(
            String symbol,
            String name,
            long issuedShares,
            long tradableShares,
            BigDecimal closePrice,
            BigDecimal previousClose,
            long volume,
            BigDecimal turnover,
            BigDecimal volatility
    ) {
        private static RankingCandidate from(ResultSet rs) throws SQLException {
            return new RankingCandidate(
                    rs.getString("symbol"),
                    rs.getString("name"),
                    rs.getLong("issued_shares"),
                    rs.getLong("tradable_shares"),
                    rs.getBigDecimal("close_price"),
                    rs.getBigDecimal("previous_close"),
                    rs.getLong("volume"),
                    rs.getBigDecimal("turnover"),
                    null
            );
        }

        private RankingCandidate withVolatility(BigDecimal value) {
            return new RankingCandidate(
                    symbol,
                    name,
                    issuedShares,
                    tradableShares,
                    closePrice,
                    previousClose,
                    volume,
                    turnover,
                    value
            );
        }

        private BigDecimal marketCapitalization() {
            return closePrice.multiply(BigDecimal.valueOf(issuedShares));
        }

        private BigDecimal changeRate() {
            return rate(closePrice.subtract(previousClose), previousClose);
        }

        private BigDecimal turnoverRate() {
            return rate(BigDecimal.valueOf(volume), BigDecimal.valueOf(tradableShares));
        }

        private InstrumentMarketAnalyticsResponse.PeerInstrument toPeer() {
            return new InstrumentMarketAnalyticsResponse.PeerInstrument(
                    symbol,
                    name,
                    closePrice,
                    marketCapitalization(),
                    changeRate()
            );
        }
    }

    private record MarketCloseRow(LocalDate businessDate, LocalDateTime completedAt) {
    }

    private record MarketCloseState(
            boolean reportDateCloseCompleted,
            LocalDate latestCompletedDate,
            LocalDateTime latestCompletedAt
    ) {
    }
}
