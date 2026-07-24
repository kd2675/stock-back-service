package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AutoParticipantFundingPolicyResponse(
        BigDecimal recurringDepositAmount,
        BigDecimal recurringDepositIntervalValue,
        String recurringDepositIntervalUnit,
        Integer recurringDepositIntervalDays
) {
}
