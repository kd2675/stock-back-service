package stock.back.service.market.biz;

import stock.back.service.database.entity.RecurringCashIntervalUnit;

import java.math.BigDecimal;

record ProfileConfigDefaults(
        BigDecimal newsWeight,
        BigDecimal momentumWeight,
        BigDecimal contrarianWeight,
        BigDecimal lossAversionWeight,
        BigDecimal herdingWeight,
        BigDecimal marketMakingWeight,
        BigDecimal overconfidenceWeight,
        BigDecimal noiseWeight,
        BigDecimal panicSellWeight,
        BigDecimal dipBuyWeight,
        BigDecimal orderMultiplier,
        BigDecimal aggressionMultiplier,
        BigDecimal pricePressureSensitivity,
        BigDecimal orderTtlMultiplier,
        BigDecimal quantityMultiplier,
        BigDecimal holdingPatienceWeight,
        BigDecimal deepLossHoldWeight,
        BigDecimal profitTakingWeight,
        BigDecimal recurringDepositAmount,
        BigDecimal recurringDepositIntervalValue,
        RecurringCashIntervalUnit recurringDepositIntervalUnit
) {
    ProfileConfigDefaults withPricePressureSensitivity(double sensitivity) {
        return new ProfileConfigDefaults(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                noiseWeight,
                panicSellWeight,
                dipBuyWeight,
                orderMultiplier,
                aggressionMultiplier,
                BigDecimal.valueOf(sensitivity),
                orderTtlMultiplier,
                quantityMultiplier,
                holdingPatienceWeight,
                deepLossHoldWeight,
                profitTakingWeight,
                recurringDepositAmount,
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit
        );
    }
}
