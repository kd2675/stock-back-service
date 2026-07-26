package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AutoParticipantWithdrawalAuditResponse(
        long withdrawalId,
        String participantUserKey,
        long sourceAccountId,
        String sourceAccountStatus,
        BigDecimal sourceRemainingCashAmount,
        long sourceRemainingShareQuantity,
        long sourceRemainingReservedShareQuantity,
        long sourceOpenOrderCount,
        long pendingCorporateActionRightCount,
        BigDecimal returnedCashAmount,
        long returnedShareQuantity,
        int returnedSymbolCount,
        String createdBy,
        LocalDateTime createdAt,
        List<AutoParticipantShareTransferResponse> shareTransfers
) {
    public AutoParticipantWithdrawalAuditResponse {
        sourceRemainingCashAmount = sourceRemainingCashAmount == null
                ? BigDecimal.ZERO
                : sourceRemainingCashAmount;
        returnedCashAmount = returnedCashAmount == null ? BigDecimal.ZERO : returnedCashAmount;
        shareTransfers = shareTransfers == null ? List.of() : List.copyOf(shareTransfers);
    }
}
