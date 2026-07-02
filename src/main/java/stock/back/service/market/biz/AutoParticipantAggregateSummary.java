package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

final class AutoParticipantAggregateSummary implements AutoParticipantAggregateTarget {

    private BigDecimal reservedBuyCash = BigDecimal.ZERO;
    private BigDecimal holdingMarketValue = BigDecimal.ZERO;
    private BigDecimal netCashFlow = BigDecimal.ZERO;
    private long holdingCount;
    private long totalHoldingQuantity;
    private long reservedSellQuantity;
    private long openOrderCount;
    private long openBuyOrderCount;
    private long openSellOrderCount;
    private long openBuyQuantity;
    private long openSellQuantity;
    private long todayExecutionCount;
    private long todayBuyQuantity;
    private long todaySellQuantity;
    private BigDecimal todayGrossAmount = BigDecimal.ZERO;
    private long strategyCount;
    private long enabledStrategyCount;
    private LocalDateTime lastOrderAt;
    private LocalDateTime lastExecutionAt;

    @Override
    public void addOpenOrderSummary(
            BigDecimal nextReservedBuyCash,
            long nextOpenOrderCount,
            long nextOpenBuyOrderCount,
            long nextOpenSellOrderCount,
            long nextOpenBuyQuantity,
            long nextOpenSellQuantity
    ) {
        reservedBuyCash = reservedBuyCash.add(nextReservedBuyCash);
        openOrderCount += nextOpenOrderCount;
        openBuyOrderCount += nextOpenBuyOrderCount;
        openSellOrderCount += nextOpenSellOrderCount;
        openBuyQuantity += nextOpenBuyQuantity;
        openSellQuantity += nextOpenSellQuantity;
    }

    @Override
    public void recordLastOrderAt(LocalDateTime nextLastOrderAt) {
        lastOrderAt = AutoParticipantAggregateQuerySupport.max(lastOrderAt, nextLastOrderAt);
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
        if (quantity > 0) {
            holdingCount++;
        }
        totalHoldingQuantity += quantity;
        reservedSellQuantity += reservedQuantity;
        holdingMarketValue = holdingMarketValue.add(marketValue);
    }

    @Override
    public void addNetCashFlow(BigDecimal nextNetCashFlow) {
        netCashFlow = netCashFlow.add(nextNetCashFlow);
    }

    @Override
    public void addTodayExecutionSummary(
            long nextTodayExecutionCount,
            long nextTodayBuyQuantity,
            long nextTodaySellQuantity,
            BigDecimal nextTodayGrossAmount
    ) {
        todayExecutionCount += nextTodayExecutionCount;
        todayBuyQuantity += nextTodayBuyQuantity;
        todaySellQuantity += nextTodaySellQuantity;
        todayGrossAmount = todayGrossAmount.add(nextTodayGrossAmount);
    }

    @Override
    public void recordLastExecutionAt(LocalDateTime nextLastExecutionAt) {
        lastExecutionAt = AutoParticipantAggregateQuerySupport.max(lastExecutionAt, nextLastExecutionAt);
    }

    @Override
    public void addStrategySummary(long nextStrategyCount, long nextEnabledStrategyCount) {
        strategyCount += nextStrategyCount;
        enabledStrategyCount += nextEnabledStrategyCount;
    }

    AutoParticipantAssetSummary toAssetSummary(BigDecimal availableCash) {
        return AutoParticipantAssetSummary.from(
                availableCash,
                reservedBuyCash,
                holdingMarketValue,
                netCashFlow
        );
    }

    BigDecimal reservedBuyCash() {
        return reservedBuyCash;
    }

    BigDecimal holdingMarketValue() {
        return holdingMarketValue;
    }

    BigDecimal netCashFlow() {
        return netCashFlow;
    }

    long holdingCount() {
        return holdingCount;
    }

    long totalHoldingQuantity() {
        return totalHoldingQuantity;
    }

    long reservedSellQuantity() {
        return reservedSellQuantity;
    }

    long openOrderCount() {
        return openOrderCount;
    }

    long openBuyOrderCount() {
        return openBuyOrderCount;
    }

    long openSellOrderCount() {
        return openSellOrderCount;
    }

    long openBuyQuantity() {
        return openBuyQuantity;
    }

    long openSellQuantity() {
        return openSellQuantity;
    }

    long todayExecutionCount() {
        return todayExecutionCount;
    }

    long todayBuyQuantity() {
        return todayBuyQuantity;
    }

    long todaySellQuantity() {
        return todaySellQuantity;
    }

    BigDecimal todayGrossAmount() {
        return todayGrossAmount;
    }

    long strategyCount() {
        return strategyCount;
    }

    long enabledStrategyCount() {
        return enabledStrategyCount;
    }

    LocalDateTime lastOrderAt() {
        return lastOrderAt;
    }

    LocalDateTime lastExecutionAt() {
        return lastExecutionAt;
    }
}
