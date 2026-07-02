package stock.back.service.market.biz;

import java.time.LocalDateTime;

record AutoParticipantSymbolConfigTarget(
        String userKey,
        LocalDateTime updatedAt
) {
}
