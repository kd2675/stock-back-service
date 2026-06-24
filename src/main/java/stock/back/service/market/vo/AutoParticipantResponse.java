package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AutoParticipantResponse(
        String userKey,
        String displayName,
        boolean enabled,
        String profileType,
        BigDecimal cashBalance,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime withdrawnAt
) {
}
