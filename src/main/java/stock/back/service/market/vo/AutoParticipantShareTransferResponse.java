package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AutoParticipantShareTransferResponse(
        String symbol,
        long receiverAccountId,
        String receiverUserKey,
        String receiverRole,
        String transferReason,
        String receiverAccountStatus,
        String receiverSelfTradeGroupId,
        long quantity,
        BigDecimal sourceAveragePrice,
        long receiverCurrentQuantity,
        long receiverReservedQuantity,
        BigDecimal receiverAveragePrice,
        BigDecimal currentPrice,
        BigDecimal transferMarketValue,
        LocalDateTime createdAt
) {
}
