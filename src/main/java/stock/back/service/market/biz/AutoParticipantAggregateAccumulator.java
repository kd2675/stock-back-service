package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

abstract class AutoParticipantAggregateAccumulator implements AutoParticipantAggregateTarget {

    protected final AutoParticipantAggregateSummary aggregate = new AutoParticipantAggregateSummary();

    @Override
    public void addOpenOrderSummary(
            BigDecimal nextReservedBuyCash,
            long nextOpenOrderCount,
            long nextOpenBuyOrderCount,
            long nextOpenSellOrderCount,
            long nextOpenBuyQuantity,
            long nextOpenSellQuantity
    ) {
        aggregate.addOpenOrderSummary(
                nextReservedBuyCash,
                nextOpenOrderCount,
                nextOpenBuyOrderCount,
                nextOpenSellOrderCount,
                nextOpenBuyQuantity,
                nextOpenSellQuantity
        );
    }

    @Override
    public void recordLastOrderAt(LocalDateTime nextLastOrderAt) {
        aggregate.recordLastOrderAt(nextLastOrderAt);
    }

    @Override
    public void addHoldingSummary(
            String symbol,
            long quantity,
            long reservedQuantity,
            long availableQuantity,
            BigDecimal marketValue,
            BigDecimal unrealizedProfit
    ) {
        aggregate.addHoldingSummary(symbol, quantity, reservedQuantity, availableQuantity, marketValue, unrealizedProfit);
        afterHoldingSummaryAdded(symbol, quantity, reservedQuantity, availableQuantity, marketValue, unrealizedProfit);
    }

    @Override
    public void addNetCashFlow(BigDecimal nextNetCashFlow) {
        aggregate.addNetCashFlow(nextNetCashFlow);
    }

    @Override
    public void addTodayExecutionSummary(
            long nextTodayExecutionCount,
            long nextTodayBuyQuantity,
            long nextTodaySellQuantity,
            BigDecimal nextTodayGrossAmount
    ) {
        aggregate.addTodayExecutionSummary(
                nextTodayExecutionCount,
                nextTodayBuyQuantity,
                nextTodaySellQuantity,
                nextTodayGrossAmount
        );
    }

    @Override
    public void recordLastExecutionAt(LocalDateTime nextLastExecutionAt) {
        aggregate.recordLastExecutionAt(nextLastExecutionAt);
    }

    @Override
    public void addStrategySummary(long nextStrategyCount, long nextEnabledStrategyCount) {
        aggregate.addStrategySummary(nextStrategyCount, nextEnabledStrategyCount);
    }

    protected void afterHoldingSummaryAdded(
            String symbol,
            long quantity,
            long reservedQuantity,
            long availableQuantity,
            BigDecimal marketValue,
            BigDecimal unrealizedProfit
    ) {
    }
}
