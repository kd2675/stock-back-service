package stock.back.service.trading.vo;

import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(
        Long id,
        Long accountId,
        String clientOrderId,
        String symbol,
        MarketType marketType,
        OrderSide side,
        OrderType orderType,
        OrderStatus status,
        BigDecimal limitPrice,
        long quantity,
        long filledQuantity,
        BigDecimal averageFillPrice,
        BigDecimal reservedCash,
        LocalDateTime createdAt
) {
}
