package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PriceTickResponse(
        String symbol,
        BigDecimal price,
        String provider,
        LocalDateTime priceTime
) {
}
