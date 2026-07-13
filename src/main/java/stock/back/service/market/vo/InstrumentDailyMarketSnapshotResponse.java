package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record InstrumentDailyMarketSnapshotResponse(
        long tradeCount,
        long volume,
        BigDecimal turnover,
        BigDecimal turnoverRate,
        BigDecimal vwap,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal lastPrice,
        LocalDateTime lastExecutedAt
) {
}
