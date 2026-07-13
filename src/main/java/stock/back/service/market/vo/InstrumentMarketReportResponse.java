package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record InstrumentMarketReportResponse(
        String symbol,
        String name,
        String market,
        BigDecimal closePrice,
        BigDecimal previousClose,
        BigDecimal changeAmount,
        BigDecimal changeRate,
        BigDecimal initialPrice,
        BigDecimal returnSinceListing,
        long issuedShares,
        long tradableShares,
        BigDecimal tradableShareRate,
        BigDecimal marketCapitalization,
        BigDecimal tradableMarketCapitalization,
        BigDecimal priceLimitRate,
        BigDecimal lowerLimitPrice,
        BigDecimal upperLimitPrice,
        LocalDateTime closePriceTime,
        String closePriceProvider,
        LocalDate reportDate,
        LocalDateTime simulationDateTime,
        InstrumentDailyMarketSnapshotResponse daily,
        InstrumentReportResponse latestEvaluation,
        InstrumentMarketAnalyticsResponse analytics
) {
}
