package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderBookTradeSummaryResponse(
        String symbol,
        long todayExecutionCount,
        long todayVolume,
        BigDecimal todayTurnover,
        BigDecimal vwap,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        long buyVolume,
        long sellVolume,
        BigDecimal buyTurnover,
        BigDecimal sellTurnover,
        BigDecimal executionStrength,
        BigDecimal lastPrice,
        LocalDateTime lastExecutedAt
) {
}
