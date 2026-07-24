package stock.back.service.market.biz;

import java.math.BigDecimal;

import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.RecurringCashIntervalUnit;
import stock.back.service.market.vo.AutoParticipantFundingPolicyRequest;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;

record AutoParticipantFundingPolicyCommand(
        BigDecimal recurringDepositAmount,
        BigDecimal recurringDepositIntervalValue,
        RecurringCashIntervalUnit recurringDepositIntervalUnit
) {

    static AutoParticipantFundingPolicyCommand from(
            AutoParticipantProfileType profileType,
            AutoParticipantProfileConfigRequest request
    ) {
        AutoParticipantFundingPolicyRequest fundingPolicy = request.fundingPolicy();
        BigDecimal amount = fundingPolicy == null
                ? request.recurringDepositAmount()
                : fundingPolicy.recurringDepositAmount();
        BigDecimal intervalValue = fundingPolicy == null
                ? request.recurringDepositIntervalValue()
                : fundingPolicy.recurringDepositIntervalValue();
        String intervalUnit = fundingPolicy == null
                ? request.recurringDepositIntervalUnit()
                : fundingPolicy.recurringDepositIntervalUnit();
        Integer legacyIntervalDays = fundingPolicy == null
                ? request.recurringDepositIntervalDays()
                : fundingPolicy.recurringDepositIntervalDays();

        BigDecimal normalizedAmount = NumericRangePolicy.requireBigDecimal(
                amount,
                "Recurring deposit amount",
                BigDecimal.ZERO,
                new BigDecimal("1000000000000")
        );
        BigDecimal normalizedIntervalValue = RecurringCashPolicy.normalizeIntervalValue(
                intervalValue == null && legacyIntervalDays != null
                        ? BigDecimal.valueOf(legacyIntervalDays)
                        : intervalValue,
                normalizedAmount
        );
        RecurringCashIntervalUnit normalizedIntervalUnit = RecurringCashPolicy.normalizeIntervalUnit(
                intervalUnit == null && legacyIntervalDays != null
                        ? RecurringCashIntervalUnit.DAY.name()
                        : intervalUnit,
                normalizedAmount
        );
        if (normalizedIntervalValue == null) {
            normalizedIntervalValue = BigDecimal.ZERO;
        }
        if (normalizedIntervalUnit == null) {
            normalizedIntervalUnit = RecurringCashIntervalUnit.DAY;
        }
        if (AutoParticipantProfileType.DIVIDEND_REINVESTOR.equals(profileType)) {
            normalizedAmount = BigDecimal.ZERO;
            normalizedIntervalValue = BigDecimal.ZERO;
            normalizedIntervalUnit = RecurringCashIntervalUnit.DAY;
        }
        return new AutoParticipantFundingPolicyCommand(
                normalizedAmount,
                normalizedIntervalValue,
                normalizedIntervalUnit
        );
    }
}
