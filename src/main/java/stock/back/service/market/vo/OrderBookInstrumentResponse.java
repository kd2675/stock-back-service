package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderBookInstrumentResponse(
        String symbol,
        String name,
        String market,
        BigDecimal initialPrice,
        long issuedShares,
        long tradableShares,
        BigDecimal tickSize,
        BigDecimal priceLimitRate,
        BigDecimal priceLimitBase,
        BigDecimal currentPrice,
        LocalDateTime priceTime,
        String priceProvider,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
