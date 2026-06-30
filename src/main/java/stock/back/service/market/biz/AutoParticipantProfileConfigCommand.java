package stock.back.service.market.biz;

import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.RecurringCashIntervalUnit;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;

import java.math.BigDecimal;

record AutoParticipantProfileConfigCommand(
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
        BigDecimal orderTtlMultiplier,
        BigDecimal quantityMultiplier,
        BigDecimal holdingPatienceWeight,
        BigDecimal deepLossHoldWeight,
        BigDecimal profitTakingWeight,
        BigDecimal recurringDepositAmount,
        BigDecimal recurringDepositIntervalValue,
        RecurringCashIntervalUnit recurringDepositIntervalUnit
) {

    static AutoParticipantProfileConfigCommand from(
            AutoParticipantProfileType profileType,
            AutoParticipantProfileConfigRequest request
    ) {
        BigDecimal recurringDepositAmount = NumericRangePolicy.requireBigDecimal(
                request.recurringDepositAmount(),
                "Recurring deposit amount",
                BigDecimal.ZERO,
                new BigDecimal("1000000000000")
        );
        BigDecimal recurringDepositIntervalValue = RecurringCashPolicy.normalizeIntervalValue(
                request.recurringDepositIntervalValue() == null && request.recurringDepositIntervalDays() != null
                        ? BigDecimal.valueOf(request.recurringDepositIntervalDays())
                        : request.recurringDepositIntervalValue(),
                recurringDepositAmount
        );
        RecurringCashIntervalUnit recurringDepositIntervalUnit = RecurringCashPolicy.normalizeIntervalUnit(
                request.recurringDepositIntervalUnit() == null && request.recurringDepositIntervalDays() != null
                        ? RecurringCashIntervalUnit.DAY.name()
                        : request.recurringDepositIntervalUnit(),
                recurringDepositAmount
        );
        if (recurringDepositIntervalValue == null) {
            recurringDepositIntervalValue = BigDecimal.ZERO;
        }
        if (recurringDepositIntervalUnit == null) {
            recurringDepositIntervalUnit = RecurringCashIntervalUnit.DAY;
        }
        if (AutoParticipantProfileType.DIVIDEND_REINVESTOR.equals(profileType)) {
            recurringDepositAmount = BigDecimal.ZERO;
            recurringDepositIntervalValue = BigDecimal.ZERO;
            recurringDepositIntervalUnit = RecurringCashIntervalUnit.DAY;
        }
        return new AutoParticipantProfileConfigCommand(
                NumericRangePolicy.requireBigDecimal(request.newsWeight(), "News weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.momentumWeight(), "Momentum weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.contrarianWeight(), "Contrarian weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.lossAversionWeight(), "Loss aversion weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.herdingWeight(), "Herding weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.marketMakingWeight(), "Market making weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.overconfidenceWeight(), "Overconfidence weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.noiseWeight(), "Noise weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.panicSellWeight(), "Panic sell weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.dipBuyWeight(), "Dip buy weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.orderMultiplier(), "Order multiplier", BigDecimal.ZERO, BigDecimal.valueOf(5)),
                NumericRangePolicy.requireBigDecimal(request.aggressionMultiplier(), "Aggression multiplier", BigDecimal.ZERO, BigDecimal.valueOf(5)),
                NumericRangePolicy.requireBigDecimal(request.orderTtlMultiplier(), "Order TTL multiplier", new BigDecimal("0.1"), BigDecimal.TEN),
                NumericRangePolicy.requireBigDecimal(request.quantityMultiplier(), "Quantity multiplier", BigDecimal.ZERO, BigDecimal.valueOf(5)),
                NumericRangePolicy.requireBigDecimal(request.holdingPatienceWeight(), "Holding patience weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.deepLossHoldWeight(), "Deep loss hold weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.profitTakingWeight(), "Profit taking weight", BigDecimal.ZERO, BigDecimal.ONE),
                recurringDepositAmount,
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit
        );
    }

    StockAutoParticipantProfileConfig create(AutoParticipantProfileType profileType) {
        return StockAutoParticipantProfileConfig.create(
                profileType,
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

    void applyTo(StockAutoParticipantProfileConfig config) {
        config.update(
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
