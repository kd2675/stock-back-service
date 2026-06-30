package stock.back.service.market.biz;

import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;

import stock.back.service.database.entity.RecurringCashIntervalUnit;

import java.math.BigDecimal;

final class AutoParticipantProfileConfigResponseMapper {

    private AutoParticipantProfileConfigResponseMapper() {
    }

    static AutoParticipantProfileConfigResponse toResponse(
            AutoParticipantProfileType profileType,
            StockAutoParticipantProfileConfig savedConfig
    ) {
        ProfileConfigDefaults defaults = AutoParticipantProfileConfigDefaults.defaultsFor(profileType);
        if (savedConfig == null) {
            return new AutoParticipantProfileConfigResponse(
                    profileType.name(),
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
                    defaults.aggressionMultiplier(),
                    defaults.orderTtlMultiplier(),
                    defaults.quantityMultiplier(),
                    defaults.holdingPatienceWeight(),
                    defaults.deepLossHoldWeight(),
                    defaults.profitTakingWeight(),
                    defaults.recurringDepositAmount(),
                    defaults.recurringDepositIntervalValue(),
                    defaults.recurringDepositIntervalUnit().name(),
                    RecurringCashPolicy.intervalDays(defaults.recurringDepositIntervalValue(), defaults.recurringDepositIntervalUnit()),
                    false,
                    null
            );
        }
        BigDecimal recurringDepositIntervalValue = valueOrDefault(
                savedConfig.getRecurringDepositIntervalValue(),
                defaults.recurringDepositIntervalValue()
        );
        RecurringCashIntervalUnit recurringDepositIntervalUnit = savedConfig.getRecurringDepositIntervalUnit() == null
                ? defaults.recurringDepositIntervalUnit()
                : savedConfig.getRecurringDepositIntervalUnit();
        return new AutoParticipantProfileConfigResponse(
                profileType.name(),
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
                savedConfig.getAggressionMultiplier(),
                savedConfig.getOrderTtlMultiplier(),
                savedConfig.getQuantityMultiplier(),
                savedConfig.getHoldingPatienceWeight(),
                savedConfig.getDeepLossHoldWeight(),
                savedConfig.getProfitTakingWeight(),
                savedConfig.getRecurringDepositAmount(),
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit.name(),
                savedConfig.getRecurringDepositIntervalDays(),
                true,
                savedConfig.getUpdatedAt()
        );
    }

    private static BigDecimal valueOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null ? defaultValue : value;
    }
}
