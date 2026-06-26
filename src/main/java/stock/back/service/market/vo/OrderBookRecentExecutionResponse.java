package stock.back.service.market.vo;

import stock.back.service.database.entity.OrderSide;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderBookRecentExecutionResponse(
        Long id,
        String symbol,
        OrderSide side,
        long quantity,
        BigDecimal price,
        BigDecimal grossAmount,
        BigDecimal priceChange,
        LocalDateTime executedAt
) {
}
