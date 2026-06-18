package stock.back.service.trading.vo;

import java.math.BigDecimal;

public record AccountResponse(
        String userKey,
        BigDecimal cashBalance,
        BigDecimal initialCash
) {
}
