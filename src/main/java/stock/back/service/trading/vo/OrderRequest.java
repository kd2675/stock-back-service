package stock.back.service.trading.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderType;

import java.math.BigDecimal;

public record OrderRequest(
        @NotBlank String symbol,
        @NotNull OrderSide side,
        @NotNull OrderType orderType,
        BigDecimal limitPrice,
        @Positive long quantity,
        String clientOrderId
) {
    public OrderRequest(String symbol, OrderSide side, OrderType orderType, BigDecimal limitPrice, long quantity) {
        this(symbol, side, orderType, limitPrice, quantity, null);
    }
}
