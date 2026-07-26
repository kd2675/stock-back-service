package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record SecurityAllocationResponse(
        long allocationId,
        String idempotencyKey,
        String eventType,
        Long corporateActionId,
        Long underwritingContractId,
        Long sourceAccountId,
        long destinationAccountId,
        String destinationAccountCode,
        String destinationParticipantCategory,
        String symbol,
        long quantity,
        BigDecimal unitPrice,
        String allocationReason,
        String tradabilityStatus,
        LocalDate effectiveBusinessDate,
        LocalDate unlockBusinessDate,
        long currentHoldingQuantity,
        long currentReservedQuantity,
        BigDecimal currentAveragePrice,
        LocalDateTime createdAt
) {
}
