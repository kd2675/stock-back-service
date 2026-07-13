package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.InstrumentDailyMarketSnapshotResponse;
import stock.back.service.market.vo.InstrumentMarketReportResponse;
import stock.back.service.market.vo.InstrumentReportResponse;
import web.common.core.simulation.SimulationClockSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class InstrumentMarketReportQueryService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbcClient;
    private final InstrumentReportService instrumentReportService;
    private final SimulationClockService simulationClockService;
    private final InstrumentMarketReportAnalyticsQueryService analyticsQueryService;

    public InstrumentMarketReportQueryService(
            JdbcTemplate jdbcTemplate,
            InstrumentReportService instrumentReportService,
            SimulationClockService simulationClockService,
            InstrumentMarketReportAnalyticsQueryService analyticsQueryService
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.instrumentReportService = instrumentReportService;
        this.simulationClockService = simulationClockService;
        this.analyticsQueryService = analyticsQueryService;
    }

    @Transactional(readOnly = true)
    public InstrumentMarketReportResponse getInstrumentMarketReport(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        InstrumentRow instrument = findInstrument(normalizedSymbol);
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime simulationDateTime = clock.simulationDateTime();
        ReportBasis reportBasis = findLatestCompletedReportBasis(normalizedSymbol, clock.simulationDate());
        LocalDate reportDate = reportBasis == null ? null : reportBasis.reportDate();
        InstrumentDailyMarketSnapshotResponse daily = reportBasis == null
                ? emptyDailySnapshot()
                : findDailySnapshot(
                        normalizedSymbol,
                        reportBasis.tradableShares(),
                        reportDate.atStartOfDay(),
                        reportDate.plusDays(1).atStartOfDay()
                );
        InstrumentReportResponse latestEvaluation = instrumentReportService.getLatestInstrumentReport(normalizedSymbol);

        BigDecimal closePrice = reportBasis == null ? BigDecimal.ZERO : reportBasis.closePrice();
        BigDecimal previousClose = reportBasis == null ? BigDecimal.ZERO : reportBasis.previousClose();
        BigDecimal changeAmount = closePrice.subtract(previousClose);
        long issuedShares = reportBasis == null ? 0L : reportBasis.issuedShares();
        long tradableShares = reportBasis == null ? 0L : reportBasis.tradableShares();
        BigDecimal priceLimitRate = reportBasis == null ? instrument.priceLimitRate() : reportBasis.priceLimitRate();
        BigDecimal lowerLimitPrice = KoreanStockTickSizePolicy.ceilingValidQuotePrice(
                instrument.market(),
                previousClose.multiply(ONE_HUNDRED.subtract(priceLimitRate)).divide(ONE_HUNDRED)
        );
        BigDecimal upperLimitPrice = KoreanStockTickSizePolicy.floorValidQuotePrice(
                instrument.market(),
                previousClose.multiply(ONE_HUNDRED.add(priceLimitRate)).divide(ONE_HUNDRED)
        );
        return new InstrumentMarketReportResponse(
                instrument.symbol(),
                instrument.name(),
                instrument.market(),
                closePrice,
                previousClose,
                changeAmount,
                rate(changeAmount, previousClose),
                instrument.initialPrice(),
                rate(closePrice.subtract(instrument.initialPrice()), instrument.initialPrice()),
                issuedShares,
                tradableShares,
                rate(BigDecimal.valueOf(tradableShares), BigDecimal.valueOf(issuedShares)),
                closePrice.multiply(BigDecimal.valueOf(issuedShares)),
                closePrice.multiply(BigDecimal.valueOf(tradableShares)),
                priceLimitRate,
                lowerLimitPrice,
                upperLimitPrice,
                reportBasis == null ? null : reportBasis.priceTime(),
                reportBasis == null ? null : reportBasis.priceProvider(),
                reportDate,
                simulationDateTime,
                daily,
                latestEvaluation,
                analyticsQueryService.getAnalytics(
                        normalizedSymbol,
                        reportBasis == null ? null : reportBasis.closeRunId(),
                        issuedShares,
                        tradableShares,
                        closePrice,
                        reportBasis == null ? null : reportBasis.priceTime(),
                        reportBasis == null ? null : reportBasis.priceProvider(),
                        daily,
                        reportDate,
                        clock
                )
        );
    }

    private ReportBasis findLatestCompletedReportBasis(String symbol, LocalDate currentSimulationDate) {
        return jdbcClient.sql(
                        """
                        select snapshot.close_run_id,
                               snapshot.simulation_trade_date,
                               snapshot.close_price,
                               snapshot.previous_close,
                               snapshot.issued_shares,
                               snapshot.tradable_shares,
                               snapshot.price_limit_rate,
                               snapshot.price_time,
                               snapshot.price_provider
                          from stock_order_book_daily_snapshot snapshot
                          join stock_market_close_run close_run
                            on close_run.id = snapshot.close_run_id
                           and close_run.symbol is null
                           and close_run.status = 'COMPLETED'
                         where snapshot.symbol = ?
                           and snapshot.simulation_trade_date <= ?
                         order by snapshot.simulation_trade_date desc,
                                  snapshot.close_run_id desc,
                                  snapshot.id desc
                         limit 1
                        """
                )
                .params(symbol, currentSimulationDate)
                .query((rs, rowNum) -> new ReportBasis(
                        rs.getLong("close_run_id"),
                        rs.getObject("simulation_trade_date", LocalDate.class),
                        rs.getBigDecimal("close_price"),
                        rs.getBigDecimal("previous_close"),
                        rs.getLong("issued_shares"),
                        rs.getLong("tradable_shares"),
                        rs.getBigDecimal("price_limit_rate"),
                        MarketQuerySupport.toDateTime(rs.getTimestamp("price_time")),
                        rs.getString("price_provider")
                ))
                .optional()
                .orElse(null);
    }

    private InstrumentDailyMarketSnapshotResponse emptyDailySnapshot() {
        return new InstrumentDailyMarketSnapshotResponse(
                0L,
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );
    }

    private InstrumentRow findInstrument(String symbol) {
        String sql = """
                select i.symbol,
                       i.name,
                       i.market,
                       i.initial_price,
                       i.price_limit_rate
                  from stock_order_book_instrument i
                 where i.symbol = ?
                   and i.enabled = true
                """;
        return jdbcClient.sql(sql)
                .param(symbol)
                .query((rs, rowNum) -> toInstrumentRow(rs))
                .optional()
                .orElseThrow(() -> StockException.notFound("Unknown stock symbol: " + symbol));
    }

    private InstrumentDailyMarketSnapshotResponse findDailySnapshot(
            String symbol,
            long tradableShares,
            LocalDateTime dayStart,
            LocalDateTime dayEnd
    ) {
        String sql = """
                select count(*) as trade_count,
                       coalesce(sum(quantity), 0) as volume,
                       coalesce(sum(gross_amount), 0) as turnover,
                       coalesce(max(price), 0) as high_price,
                       coalesce(min(price), 0) as low_price,
                       coalesce((select e.price
                                   from stock_execution e
                                  where e.symbol = ?
                                    and e.source = 'INTERNAL_ORDER_BOOK'
                                    and e.side = 'BUY'
                                    and e.executed_at >= ?
                                    and e.executed_at < ?
                                  order by e.executed_at asc, e.id asc
                                  limit 1), 0) as open_price,
                       coalesce((select e.price
                                   from stock_execution e
                                  where e.symbol = ?
                                    and e.source = 'INTERNAL_ORDER_BOOK'
                                    and e.side = 'BUY'
                                    and e.executed_at >= ?
                                    and e.executed_at < ?
                                  order by e.executed_at desc, e.id desc
                                  limit 1), 0) as last_price,
                       (select e.executed_at
                          from stock_execution e
                         where e.symbol = ?
                           and e.source = 'INTERNAL_ORDER_BOOK'
                           and e.side = 'BUY'
                           and e.executed_at >= ?
                           and e.executed_at < ?
                         order by e.executed_at desc, e.id desc
                         limit 1) as last_executed_at
                  from stock_execution
                 where symbol = ?
                   and source = 'INTERNAL_ORDER_BOOK'
                   and side = 'BUY'
                   and executed_at >= ?
                   and executed_at < ?
                """;
        return jdbcClient.sql(sql)
                .params(
                        symbol, dayStart, dayEnd,
                        symbol, dayStart, dayEnd,
                        symbol, dayStart, dayEnd,
                        symbol, dayStart, dayEnd
                )
                .query((rs, rowNum) -> {
                    long volume = rs.getLong("volume");
                    BigDecimal turnover = MarketQuerySupport.zeroIfNull(rs.getBigDecimal("turnover"));
                    return new InstrumentDailyMarketSnapshotResponse(
                            rs.getLong("trade_count"),
                            volume,
                            turnover,
                            rate(BigDecimal.valueOf(volume), BigDecimal.valueOf(tradableShares)),
                            volume <= 0 ? BigDecimal.ZERO : turnover.divide(BigDecimal.valueOf(volume), 4, RoundingMode.HALF_UP),
                            MarketQuerySupport.zeroIfNull(rs.getBigDecimal("open_price")),
                            MarketQuerySupport.zeroIfNull(rs.getBigDecimal("high_price")),
                            MarketQuerySupport.zeroIfNull(rs.getBigDecimal("low_price")),
                            MarketQuerySupport.zeroIfNull(rs.getBigDecimal("last_price")),
                            MarketQuerySupport.toDateTime(rs.getTimestamp("last_executed_at"))
                    );
                })
                .single();
    }

    private InstrumentRow toInstrumentRow(ResultSet rs) throws SQLException {
        return new InstrumentRow(
                rs.getString("symbol"),
                rs.getString("name"),
                rs.getString("market"),
                rs.getBigDecimal("initial_price"),
                rs.getBigDecimal("price_limit_rate")
        );
    }

    private static BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(ONE_HUNDRED).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private record InstrumentRow(
            String symbol,
            String name,
            String market,
            BigDecimal initialPrice,
            BigDecimal priceLimitRate
    ) {
    }

    private record ReportBasis(
            long closeRunId,
            LocalDate reportDate,
            BigDecimal closePrice,
            BigDecimal previousClose,
            long issuedShares,
            long tradableShares,
            BigDecimal priceLimitRate,
            LocalDateTime priceTime,
            String priceProvider
    ) {
    }
}
