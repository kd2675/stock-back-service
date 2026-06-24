package stock.back.service.trading.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountResponse(
        Long accountId,
        String userKey,
        String accountCode,
        String status,
        BigDecimal cashBalance,
        LocalDateTime detachedAt,
        LocalDateTime reconnectedAt,
        LocalDateTime recoveryExpiresAt,
        LocalDateTime purgeAfter,
        String recoveryCode
) {
}
