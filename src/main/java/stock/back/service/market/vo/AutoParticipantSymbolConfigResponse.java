package stock.back.service.market.vo;

import java.time.LocalDateTime;

public record AutoParticipantSymbolConfigResponse(
        String userKey,
        String symbol,
        boolean enabled,
        int intensity,
        LocalDateTime updatedAt
) {
}
