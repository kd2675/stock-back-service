package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderBookCandleResponse(
        String symbol,
        String interval,
        LocalDateTime bucketStart,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        long volume,
        BigDecimal turnover,
        long executionCount
) {
}
