package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AutoParticipantProfileConfigResponse(
        String profileType,
        String behaviorModelVersion,
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
        AutoParticipantFundingPolicyResponse fundingPolicy,
        boolean customized,
        LocalDateTime updatedAt
) {
}
