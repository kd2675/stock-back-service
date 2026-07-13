package stock.back.service.market.vo;

import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record InstrumentMarketAnalyticsResponse(
        Performance performance,
        TradingActivity tradingActivity,
        InvestorFlow investorFlow,
        Ownership ownership,
        CorporateActions corporateActions,
        Rankings rankings,
        DataQuality dataQuality
) {

    public record Performance(
            int availableTradingDays,
            BigDecimal return5Days,
            BigDecimal return20Days,
            BigDecimal return60Days,
            BigDecimal highPrice20Days,
            BigDecimal lowPrice20Days,
            BigDecimal drawdownFrom20DayHigh,
            BigDecimal dailyVolatility20Days,
            BigDecimal averageVolume20Days,
            BigDecimal volumeVsAverage20Days,
            BigDecimal averageTurnover20Days,
            BigDecimal turnoverVsAverage20Days,
            BigDecimal averageTurnoverRate20Days,
            String closeTrend20Days,
            int consecutiveUpDays,
            int consecutiveDownDays,
            List<DailyHistoryPoint> dailyHistory
    ) {
    }

    public record DailyHistoryPoint(
            LocalDate tradeDate,
            BigDecimal closePrice,
            long volume,
            BigDecimal turnover,
            long issuedShares,
            long tradableShares,
            boolean reportDate
    ) {
    }

    public record TradingActivity(
            long executionCount20Days,
            long executionQuantity20Days,
            BigDecimal averageExecutionQuantity20Days,
            BigDecimal averageSecondsBetweenTrades20Days
    ) {
    }

    public record InvestorFlow(
            List<FlowWindow> windows,
            BigDecimal autoParticipantExecutionShareRateLatestTradingDay,
            BigDecimal topAccountExecutionShareRate20Days
    ) {
    }

    public record FlowWindow(
            String window,
            int tradingDays,
            LocalDate startDate,
            LocalDate endDate,
            List<CategoryFlow> categories
    ) {
    }

    public record CategoryFlow(
            String category,
            long buyQuantity,
            long sellQuantity,
            long netQuantity,
            BigDecimal buyAmount,
            BigDecimal sellAmount,
            BigDecimal netCashFlow,
            BigDecimal buySellRatio,
            BigDecimal executionShareRate
    ) {
    }

    public record Ownership(
            long holderCount,
            long accountedHoldingQuantity,
            BigDecimal holdingCoverageRate,
            long topHolderQuantity,
            BigDecimal topHolderRate,
            long topFiveHolderQuantity,
            BigDecimal topFiveHolderRate,
            long issuedShareChange60Days,
            long tradableShareChange60Days,
            List<ShareHistoryPoint> shareHistory
    ) {
    }

    public record ShareHistoryPoint(
            LocalDate tradeDate,
            long issuedShares,
            long tradableShares,
            long issuedShareChange,
            long tradableShareChange
    ) {
    }

    public record CorporateActions(
            long announcedCount,
            long completedCount,
            BigDecimal cumulativePaidDividendPerShare,
            BigDecimal cumulativePaidDividendCash,
            List<CorporateActionMetric> events
    ) {
    }

    public record CorporateActionMetric(
            long id,
            StockCorporateActionType actionType,
            StockCorporateActionStatus status,
            StockCapitalIncreaseOfferingType offeringType,
            long shareQuantity,
            BigDecimal issuePrice,
            BigDecimal basePrice,
            BigDecimal theoreticalExRightsPrice,
            BigDecimal issueDiscountRate,
            BigDecimal newShareRate,
            BigDecimal estimatedDilutionRate,
            BigDecimal dividendPerShare,
            BigDecimal dividendYield,
            BigDecimal splitRatio,
            LocalDate exRightsDate,
            LocalDate subscriptionStartDate,
            LocalDate subscriptionEndDate,
            LocalDate paymentDate,
            LocalDate listingDate,
            LocalDate delistingDate,
            BigDecimal beforePrice,
            BigDecimal afterPrice,
            Long beforeIssuedShares,
            Long afterIssuedShares,
            BigDecimal beforeMarketCapitalization,
            BigDecimal afterMarketCapitalization,
            String description,
            LocalDateTime createdAt
    ) {
    }

    public record Rankings(
            int instrumentCount,
            MetricRank marketCapitalization,
            MetricRank turnover,
            MetricRank volume,
            MetricRank returnRate,
            MetricRank turnoverRate,
            MetricRank volatility,
            BigDecimal marketAverageReturnRate,
            BigDecimal relativeReturnRate,
            List<PeerInstrument> similarMarketCapitalizationPeers
    ) {
    }

    public record MetricRank(
            int rank,
            int total,
            BigDecimal value,
            boolean lowerIsBetter
    ) {
    }

    public record PeerInstrument(
            String symbol,
            String name,
            BigDecimal closePrice,
            BigDecimal marketCapitalization,
            BigDecimal changeRate
    ) {
    }

    public record DataQuality(
            String level,
            List<String> notes,
            List<String> limitations,
            LocalDate reportDate,
            LocalDateTime simulationDateTime,
            LocalDateTime closePriceAsOf,
            LocalDateTime lastExecutionAt,
            String priceProvider,
            String executionSource,
            int historicalTradingDays,
            LocalDate historyStartDate,
            LocalDate historyEndDate,
            boolean hasReportDateTrades,
            boolean reportDateMarketCloseCompleted,
            LocalDate latestCompletedMarketCloseDate,
            LocalDateTime latestCompletedMarketCloseAt
    ) {
    }
}
