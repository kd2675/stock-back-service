package stock.back.service.market.biz;

import java.math.BigDecimal;

import stock.back.service.database.entity.RecurringCashIntervalUnit;

record AutoParticipantFundingPolicyDefaults(
        BigDecimal recurringDepositAmount,
        BigDecimal recurringDepositIntervalValue,
        RecurringCashIntervalUnit recurringDepositIntervalUnit
) {
    static final AutoParticipantFundingPolicyDefaults NO_RECURRING_FUNDING =
            new AutoParticipantFundingPolicyDefaults(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    RecurringCashIntervalUnit.DAY
            );
}
