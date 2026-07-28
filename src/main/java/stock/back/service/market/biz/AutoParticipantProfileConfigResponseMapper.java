package stock.back.service.market.biz;

import java.math.BigDecimal;

import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.RecurringCashIntervalUnit;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.market.vo.AutoParticipantFundingPolicyResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;

final class AutoParticipantProfileConfigResponseMapper {

    private AutoParticipantProfileConfigResponseMapper() {
    }

    static AutoParticipantProfileConfigResponse toResponse(
            AutoParticipantProfileType profileType,
            StockAutoParticipantProfileConfig savedConfig
    ) {
        ProfileConfigDefaults defaults = AutoParticipantProfileConfigDefaults.defaultsFor(profileType);
        AutoParticipantFundingPolicyDefaults fundingDefaults =
                AutoParticipantProfileConfigDefaults.fundingDefaults();
        String defaultPricingMode = AutoParticipantProfileConfigDefaults.pricingModeFor(profileType).name();
        String defaultExitMode = AutoParticipantProfileConfigDefaults.exitModeFor(profileType).name();
        String defaultInventoryMode = AutoParticipantProfileConfigDefaults.inventoryModeFor(profileType).name();
        if (savedConfig == null) {
            return new AutoParticipantProfileConfigResponse(
                    profileType.name(),
                    "V3",
                    defaults.newsWeight(),
                    defaults.momentumWeight(),
                    defaults.contrarianWeight(),
                    defaults.lossAversionWeight(),
                    defaults.herdingWeight(),
                    defaults.marketMakingWeight(),
                    defaults.overconfidenceWeight(),
                    defaults.noiseWeight(),
                    defaults.panicSellWeight(),
                    defaults.dipBuyWeight(),
                    defaults.orderMultiplier(),
                    defaults.decisionFrequencyMultiplier(),
                    defaults.ordersPerDecisionMultiplier(),
                    defaults.aggressionMultiplier(),
                    defaults.pricePressureSensitivity(),
                    defaults.orderTtlMultiplier(),
                    defaults.quantityMultiplier(),
                    defaults.holdingPatienceWeight(),
                    defaults.deepLossHoldWeight(),
                    defaults.profitTakingWeight(),
                    defaultPricingMode,
                    defaultExitMode,
                    defaultInventoryMode,
                    fundingDefaults.recurringDepositAmount(),
                    fundingDefaults.recurringDepositIntervalValue(),
                    fundingDefaults.recurringDepositIntervalUnit().name(),
                    RecurringCashPolicy.intervalDays(
                            fundingDefaults.recurringDepositIntervalValue(),
                            fundingDefaults.recurringDepositIntervalUnit()
                    ),
                    new AutoParticipantFundingPolicyResponse(
                            fundingDefaults.recurringDepositAmount(),
                            fundingDefaults.recurringDepositIntervalValue(),
                            fundingDefaults.recurringDepositIntervalUnit().name(),
                            RecurringCashPolicy.intervalDays(
                                    fundingDefaults.recurringDepositIntervalValue(),
                                    fundingDefaults.recurringDepositIntervalUnit()
                            )
                    ),
                    false,
                    null
            );
        }
        BigDecimal recurringDepositIntervalValue = valueOrDefault(
                savedConfig.getRecurringDepositIntervalValue(),
                fundingDefaults.recurringDepositIntervalValue()
        );
        RecurringCashIntervalUnit recurringDepositIntervalUnit = savedConfig.getRecurringDepositIntervalUnit() == null
                ? fundingDefaults.recurringDepositIntervalUnit()
                : savedConfig.getRecurringDepositIntervalUnit();
        return new AutoParticipantProfileConfigResponse(
                profileType.name(),
                savedConfig.getBehaviorModelVersion() == null
                        ? "V3"
                        : savedConfig.getBehaviorModelVersion().name(),
                valueOrDefault(savedConfig.getNewsWeight(), defaults.newsWeight()),
                valueOrDefault(savedConfig.getMomentumWeight(), defaults.momentumWeight()),
                valueOrDefault(savedConfig.getContrarianWeight(), defaults.contrarianWeight()),
                valueOrDefault(savedConfig.getLossAversionWeight(), defaults.lossAversionWeight()),
                valueOrDefault(savedConfig.getHerdingWeight(), defaults.herdingWeight()),
                valueOrDefault(savedConfig.getMarketMakingWeight(), defaults.marketMakingWeight()),
                valueOrDefault(savedConfig.getOverconfidenceWeight(), defaults.overconfidenceWeight()),
                valueOrDefault(savedConfig.getNoiseWeight(), defaults.noiseWeight()),
                valueOrDefault(savedConfig.getPanicSellWeight(), defaults.panicSellWeight()),
                valueOrDefault(savedConfig.getDipBuyWeight(), defaults.dipBuyWeight()),
                savedConfig.getOrderMultiplier(),
                valueOrDefault(savedConfig.getDecisionFrequencyMultiplier(), defaults.decisionFrequencyMultiplier()),
                valueOrDefault(savedConfig.getOrdersPerDecisionMultiplier(), defaults.ordersPerDecisionMultiplier()),
                savedConfig.getAggressionMultiplier(),
                valueOrDefault(savedConfig.getPricePressureSensitivity(), defaults.pricePressureSensitivity()),
                savedConfig.getOrderTtlMultiplier(),
                savedConfig.getQuantityMultiplier(),
                savedConfig.getHoldingPatienceWeight(),
                savedConfig.getDeepLossHoldWeight(),
                savedConfig.getProfitTakingWeight(),
                savedConfig.getPricingMode() == null ? defaultPricingMode : savedConfig.getPricingMode().name(),
                savedConfig.getExitMode() == null ? defaultExitMode : savedConfig.getExitMode().name(),
                savedConfig.getInventoryMode() == null ? defaultInventoryMode : savedConfig.getInventoryMode().name(),
                savedConfig.getRecurringDepositAmount(),
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit.name(),
                savedConfig.getRecurringDepositIntervalDays(),
                new AutoParticipantFundingPolicyResponse(
                        savedConfig.getRecurringDepositAmount(),
                        recurringDepositIntervalValue,
                        recurringDepositIntervalUnit.name(),
                        savedConfig.getRecurringDepositIntervalDays()
                ),
                true,
                savedConfig.getUpdatedAt()
        );
    }

    private static BigDecimal valueOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null ? defaultValue : value;
    }
}
