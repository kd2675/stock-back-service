package stock.back.service.trading.vo;

import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.OrderSide;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExecutionResponse(
        Long id,
        Long accountId,
        Long orderId,
        String symbol,
        OrderSide side,
        long quantity,
        BigDecimal price,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal taxAmount,
        BigDecimal netAmount,
        BigDecimal realizedProfit,
        ExecutionSource source,
        LocalDateTime executedAt
) {
}
