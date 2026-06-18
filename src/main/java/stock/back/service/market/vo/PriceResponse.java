package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PriceResponse(
        String symbol,
        BigDecimal currentPrice,
        BigDecimal previousClose,
        BigDecimal changeRate,
        LocalDateTime priceTime,
        String provider
) {
}
