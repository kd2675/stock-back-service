package stock.back.service.market.biz;

import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.AutoParticipantBehaviorModelVersion;
import stock.back.service.database.entity.AutoParticipantProfileExitMode;
import stock.back.service.database.entity.AutoParticipantProfileInventoryMode;
import stock.back.service.database.entity.AutoParticipantProfilePricingMode;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;

import java.math.BigDecimal;

record AutoParticipantProfileConfigCommand(
        AutoParticipantBehaviorModelVersion behaviorModelVersion,
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
        BigDecimal decisionFrequencyMultiplier,
        BigDecimal ordersPerDecisionMultiplier,
        BigDecimal aggressionMultiplier,
        BigDecimal pricePressureSensitivity,
        BigDecimal orderTtlMultiplier,
        BigDecimal quantityMultiplier,
        BigDecimal holdingPatienceWeight,
        BigDecimal deepLossHoldWeight,
        BigDecimal profitTakingWeight,
        AutoParticipantProfilePricingMode pricingMode,
        AutoParticipantProfileExitMode exitMode,
        AutoParticipantProfileInventoryMode inventoryMode,
        AutoParticipantFundingPolicyCommand fundingPolicy
) {

    static AutoParticipantProfileConfigCommand from(
            AutoParticipantProfileType profileType,
            AutoParticipantProfileConfigRequest request
    ) {
        ProfileConfigDefaults profileDefaults = AutoParticipantProfileConfigDefaults.defaultsFor(profileType);
        AutoParticipantFundingPolicyCommand fundingPolicy =
                AutoParticipantFundingPolicyCommand.from(profileType, request);
        BigDecimal orderMultiplier = NumericRangePolicy.requireBigDecimal(
                request.orderMultiplier(), "Order multiplier", BigDecimal.ZERO, BigDecimal.valueOf(5)
        );
        BigDecimal orderTtlMultiplier = NumericRangePolicy.requireBigDecimal(
                request.orderTtlMultiplier(), "Order TTL multiplier", new BigDecimal("0.1"), BigDecimal.TEN
        );
        BigDecimal marketMakingWeight = NumericRangePolicy.requireBigDecimal(
                request.marketMakingWeight(), "Market making weight", BigDecimal.ZERO, BigDecimal.ONE
        );
        BigDecimal profitTakingWeight = NumericRangePolicy.requireBigDecimal(
                request.profitTakingWeight(), "Profit taking weight", BigDecimal.ZERO, BigDecimal.ONE
        );
        BigDecimal holdingPatienceWeight = NumericRangePolicy.requireBigDecimal(
                request.holdingPatienceWeight(), "Holding patience weight", BigDecimal.ZERO, BigDecimal.ONE
        );
        BigDecimal decisionFrequencyMultiplier = request.decisionFrequencyMultiplier() == null
                ? profileDefaults.decisionFrequencyMultiplier()
                : NumericRangePolicy.requireBigDecimal(
                        request.decisionFrequencyMultiplier(),
                        "Decision frequency multiplier",
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(20)
                );
        BigDecimal ordersPerDecisionMultiplier = request.ordersPerDecisionMultiplier() == null
                ? profileDefaults.ordersPerDecisionMultiplier()
                : NumericRangePolicy.requireBigDecimal(
                        request.ordersPerDecisionMultiplier(),
                        "Orders per decision multiplier",
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(5)
                );
        return new AutoParticipantProfileConfigCommand(
                enumOrDefault(
                        request.behaviorModelVersion(),
                        AutoParticipantBehaviorModelVersion.class,
                        AutoParticipantBehaviorModelVersion.V2,
                        "Behavior model version"
                ),
                NumericRangePolicy.requireBigDecimal(request.newsWeight(), "News weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.momentumWeight(), "Momentum weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.contrarianWeight(), "Contrarian weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.lossAversionWeight(), "Loss aversion weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.herdingWeight(), "Herding weight", BigDecimal.ZERO, BigDecimal.ONE),
                marketMakingWeight,
                NumericRangePolicy.requireBigDecimal(request.overconfidenceWeight(), "Overconfidence weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.noiseWeight(), "Noise weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.panicSellWeight(), "Panic sell weight", BigDecimal.ZERO, BigDecimal.ONE),
                NumericRangePolicy.requireBigDecimal(request.dipBuyWeight(), "Dip buy weight", BigDecimal.ZERO, BigDecimal.ONE),
                orderMultiplier,
                decisionFrequencyMultiplier,
                ordersPerDecisionMultiplier,
                NumericRangePolicy.requireBigDecimal(request.aggressionMultiplier(), "Aggression multiplier", BigDecimal.ZERO, BigDecimal.valueOf(5)),
                NumericRangePolicy.requireBigDecimal(request.pricePressureSensitivity(), "Price pressure sensitivity", BigDecimal.ZERO, BigDecimal.valueOf(2)),
                orderTtlMultiplier,
                NumericRangePolicy.requireBigDecimal(request.quantityMultiplier(), "Quantity multiplier", BigDecimal.ZERO, BigDecimal.valueOf(5)),
                holdingPatienceWeight,
                NumericRangePolicy.requireBigDecimal(request.deepLossHoldWeight(), "Deep loss hold weight", BigDecimal.ZERO, BigDecimal.ONE),
                profitTakingWeight,
                enumOrDefault(
                        request.pricingMode(),
                        AutoParticipantProfilePricingMode.class,
                        AutoParticipantProfileConfigDefaults.pricingModeFor(profileType),
                        "Pricing mode"
                ),
                enumOrDefault(
                        request.exitMode(),
                        AutoParticipantProfileExitMode.class,
                        AutoParticipantProfileConfigDefaults.exitModeFor(profileType),
                        "Exit mode"
                ),
                enumOrDefault(
                        request.inventoryMode(),
                        AutoParticipantProfileInventoryMode.class,
                        AutoParticipantProfileConfigDefaults.inventoryModeFor(profileType),
                        "Inventory mode"
                ),
                fundingPolicy
        );
    }

    StockAutoParticipantProfileConfig create(AutoParticipantProfileType profileType) {
        StockAutoParticipantProfileConfig config = StockAutoParticipantProfileConfig.create(
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
                decisionFrequencyMultiplier,
                ordersPerDecisionMultiplier,
                aggressionMultiplier,
                pricePressureSensitivity,
                orderTtlMultiplier,
                quantityMultiplier,
                holdingPatienceWeight,
                deepLossHoldWeight,
                profitTakingWeight,
                pricingMode,
                exitMode,
                inventoryMode,
                fundingPolicy.recurringDepositAmount(),
                fundingPolicy.recurringDepositIntervalValue(),
                fundingPolicy.recurringDepositIntervalUnit()
        );
        config.updateBehaviorModelVersion(behaviorModelVersion);
        return config;
    }

    void applyTo(StockAutoParticipantProfileConfig config) {
        config.updateBehaviorModelVersion(behaviorModelVersion);
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
                decisionFrequencyMultiplier,
                ordersPerDecisionMultiplier,
                aggressionMultiplier,
                pricePressureSensitivity,
                orderTtlMultiplier,
                quantityMultiplier,
                holdingPatienceWeight,
                deepLossHoldWeight,
                profitTakingWeight,
                pricingMode,
                exitMode,
                inventoryMode,
                fundingPolicy.recurringDepositAmount(),
                fundingPolicy.recurringDepositIntervalValue(),
                fundingPolicy.recurringDepositIntervalUnit()
        );
    }

    private static <T extends Enum<T>> T enumOrDefault(
            String value,
            Class<T> type,
            T defaultValue,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw StockException.badRequest(fieldName + " is invalid: " + value);
        }
    }
}
