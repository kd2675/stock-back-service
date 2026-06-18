package stock.back.service.market.vo;

import java.math.BigDecimal;

public record OrderBookLevelResponse(
        BigDecimal price,
        long quantity,
        long orderCount
) {
}
