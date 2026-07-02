package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

interface AutoParticipantAggregateTarget {

    void addOpenOrderSummary(
            BigDecimal reservedBuyCash,
            long openOrderCount,
            long openBuyOrderCount,
            long openSellOrderCount,
            long openBuyQuantity,
            long openSellQuantity
    );

    void recordLastOrderAt(LocalDateTime lastOrderAt);

    void addHoldingSummary(
            String symbol,
            long quantity,
            long reservedQuantity,
            long availableQuantity,
            BigDecimal marketValue,
            BigDecimal unrealizedProfit
    );

    void addNetCashFlow(BigDecimal netCashFlow);

    void addTodayExecutionSummary(
            long todayExecutionCount,
            long todayBuyQuantity,
            long todaySellQuantity,
            BigDecimal todayGrossAmount
    );

    void recordLastExecutionAt(LocalDateTime lastExecutionAt);

    void addStrategySummary(long strategyCount, long enabledStrategyCount);
}
