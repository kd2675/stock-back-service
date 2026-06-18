package stock.back.service.trading.vo;

import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.OrderSide;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExecutionResponse(
        Long id,
        Long orderId,
        String symbol,
        OrderSide side,
        long quantity,
        BigDecimal price,
        ExecutionSource source,
        LocalDateTime executedAt
) {
}
