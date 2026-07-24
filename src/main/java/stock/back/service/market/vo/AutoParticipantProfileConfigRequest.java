package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AutoParticipantProfileConfigRequest(
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
        String pricingMode,
        String exitMode,
        String inventoryMode,
        BigDecimal recurringDepositAmount,
        BigDecimal recurringDepositIntervalValue,
        String recurringDepositIntervalUnit,
        Integer recurringDepositIntervalDays,
        AutoParticipantFundingPolicyRequest fundingPolicy,
        String behaviorModelVersion
) {
    public AutoParticipantProfileConfigRequest(
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
            String pricingMode,
            String exitMode,
            String inventoryMode,
            BigDecimal recurringDepositAmount,
            BigDecimal recurringDepositIntervalValue,
            String recurringDepositIntervalUnit,
            Integer recurringDepositIntervalDays
    ) {
        this(
                newsWeight, momentumWeight, contrarianWeight, lossAversionWeight, herdingWeight,
                marketMakingWeight, overconfidenceWeight, noiseWeight, panicSellWeight, dipBuyWeight,
                orderMultiplier, decisionFrequencyMultiplier, ordersPerDecisionMultiplier,
                aggressionMultiplier, pricePressureSensitivity, orderTtlMultiplier, quantityMultiplier,
                holdingPatienceWeight, deepLossHoldWeight, profitTakingWeight, pricingMode, exitMode,
                inventoryMode, recurringDepositAmount, recurringDepositIntervalValue,
                recurringDepositIntervalUnit, recurringDepositIntervalDays, null, null
        );
    }

    public AutoParticipantProfileConfigRequest(
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
            String recurringDepositIntervalUnit,
            Integer recurringDepositIntervalDays
    ) {
        this(
                newsWeight, momentumWeight, contrarianWeight, lossAversionWeight, herdingWeight,
                marketMakingWeight, overconfidenceWeight, noiseWeight, panicSellWeight, dipBuyWeight,
                orderMultiplier, null, null, aggressionMultiplier, pricePressureSensitivity,
                orderTtlMultiplier, quantityMultiplier, holdingPatienceWeight, deepLossHoldWeight,
                profitTakingWeight, null, null, null, recurringDepositAmount,
                recurringDepositIntervalValue, recurringDepositIntervalUnit, recurringDepositIntervalDays, null, null
        );
    }

    public AutoParticipantProfileConfigRequest(
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
            String pricingMode,
            String exitMode,
            String inventoryMode,
            BigDecimal recurringDepositAmount,
            BigDecimal recurringDepositIntervalValue,
            String recurringDepositIntervalUnit,
            Integer recurringDepositIntervalDays,
            AutoParticipantFundingPolicyRequest fundingPolicy
    ) {
        this(
                newsWeight, momentumWeight, contrarianWeight, lossAversionWeight, herdingWeight,
                marketMakingWeight, overconfidenceWeight, noiseWeight, panicSellWeight, dipBuyWeight,
                orderMultiplier, decisionFrequencyMultiplier, ordersPerDecisionMultiplier,
                aggressionMultiplier, pricePressureSensitivity, orderTtlMultiplier, quantityMultiplier,
                holdingPatienceWeight, deepLossHoldWeight, profitTakingWeight, pricingMode, exitMode,
                inventoryMode, recurringDepositAmount, recurringDepositIntervalValue,
                recurringDepositIntervalUnit, recurringDepositIntervalDays, fundingPolicy, null
        );
    }
}
