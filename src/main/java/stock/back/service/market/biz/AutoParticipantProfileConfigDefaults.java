package stock.back.service.market.biz;

import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.RecurringCashIntervalUnit;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

final class AutoParticipantProfileConfigDefaults {

    private static final int DEFAULT_RECURRING_DEPOSIT_INTERVAL_DAYS = 30;
    private static final Map<AutoParticipantProfileType, ProfileConfigDefaults> DEFAULTS = createDefaults();

    private AutoParticipantProfileConfigDefaults() {
    }

    static ProfileConfigDefaults defaultsFor(AutoParticipantProfileType profileType) {
        return DEFAULTS.getOrDefault(profileType, DEFAULTS.get(AutoParticipantProfileType.defaultType()));
    }

    private static Map<AutoParticipantProfileType, ProfileConfigDefaults> createDefaults() {
        Map<AutoParticipantProfileType, ProfileConfigDefaults> defaults = new EnumMap<>(AutoParticipantProfileType.class);
        defaults.put(AutoParticipantProfileType.NEWS_REACTIVE, profileDefaults(0.85, 0.15, 0.00, 0.25, 0.20, 0.00, 0.10, 0.10, 0.00, 0.05, 1.10, 1.15, 1.00, 1.00, 0.15, 0.10, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.MOMENTUM_FOLLOWER, profileDefaults(0.25, 0.85, 0.00, 0.20, 0.35, 0.00, 0.15, 0.12, 0.05, 0.00, 1.00, 1.10, 1.00, 1.00, 0.10, 0.05, 0.25, "0.00"));
        defaults.put(AutoParticipantProfileType.CONTRARIAN, profileDefaults(0.20, 0.00, 0.85, 0.25, 0.00, 0.10, 0.05, 0.12, 0.00, 0.35, 1.00, 0.90, 1.20, 1.00, 0.20, 0.15, 0.35, "0.00"));
        defaults.put(AutoParticipantProfileType.LOSS_AVERSE, profileDefaults(0.25, 0.10, 0.00, 0.95, 0.10, 0.00, 0.05, 0.08, 0.05, 0.00, 0.85, 0.80, 1.80, 0.80, 0.75, 0.60, 0.05, "0.00"));
        defaults.put(AutoParticipantProfileType.OVERCONFIDENT, profileDefaults(0.35, 0.45, 0.00, 0.20, 0.25, 0.00, 0.95, 0.15, 0.05, 0.05, 1.25, 1.15, 0.90, 1.25, 0.10, 0.05, 0.10, "0.00"));
        defaults.put(AutoParticipantProfileType.HERD_FOLLOWER, profileDefaults(0.25, 0.25, 0.00, 0.15, 0.90, 0.00, 0.15, 0.12, 0.15, 0.00, 1.05, 1.05, 1.00, 1.00, 0.05, 0.00, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.MARKET_MAKER, profileDefaults(0.15, 0.05, 0.00, 0.10, 0.10, 0.95, 0.00, 0.08, 0.00, 0.00, 1.25, 0.65, 0.60, 1.00, 0.00, 0.00, 0.45, "0.00"));
        defaults.put(AutoParticipantProfileType.NOISE_TRADER, profileDefaults(0.35, 0.20, 0.10, 0.20, 0.15, 0.00, 0.10, 0.45, 0.05, 0.05, 1.00, 1.00, 1.00, 1.00, 0.10, 0.05, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.VALUE_ANCHOR, profileDefaults(0.20, 0.00, 0.45, 0.55, 0.00, 0.10, 0.00, 0.08, 0.00, 0.25, 0.80, 0.75, 1.60, 0.80, 0.50, 0.35, 0.15, "0.00"));
        defaults.put(AutoParticipantProfileType.SCALPER, profileDefaults(0.25, 0.55, 0.00, 0.10, 0.35, 0.00, 0.20, 0.22, 0.10, 0.00, 1.15, 1.15, 0.65, 0.65, 0.00, 0.00, 0.85, "0.00"));
        defaults.put(AutoParticipantProfileType.DAY_TRADER, profileDefaults(0.25, 0.62, 0.00, 0.08, 0.30, 0.00, 0.20, 0.18, 0.12, 0.00, 1.20, 1.15, 0.80, 0.85, 0.00, 0.00, 0.80, "0.00"));
        defaults.put(AutoParticipantProfileType.SWING_TRADER, profileDefaults(0.30, 0.45, 0.25, 0.25, 0.15, 0.00, 0.15, 0.12, 0.05, 0.20, 0.95, 1.05, 1.10, 1.05, 0.20, 0.15, 0.45, "0.00"));
        defaults.put(AutoParticipantProfileType.LONG_TERM_HOLDER, profileDefaults(0.20, 0.05, 0.20, 0.85, 0.00, 0.00, 0.00, 0.05, 0.00, 0.45, 0.45, 0.50, 2.50, 0.55, 0.95, 0.75, 0.05, "0.00"));
        defaults.put(AutoParticipantProfileType.PAYDAY_ACCUMULATOR, profileDefaults(0.20, 0.10, 0.15, 0.65, 0.05, 0.00, 0.00, 0.06, 0.00, 0.55, 0.90, 0.80, 2.00, 0.70, 0.90, 0.55, 0.05, "0.00"));
        defaults.put(AutoParticipantProfileType.DIVIDEND_REINVESTOR, profileDefaults(0.20, 0.08, 0.20, 0.70, 0.05, 0.00, 0.00, 0.05, 0.00, 0.50, 0.80, 0.75, 2.20, 0.65, 0.90, 0.65, 0.08, "0.00"));
        defaults.put(AutoParticipantProfileType.LIMIT_DOWN_TRAPPED, profileDefaults(0.20, 0.00, 0.20, 1.00, 0.05, 0.00, 0.00, 0.08, 0.00, 0.25, 0.55, 0.55, 2.50, 0.50, 1.00, 1.00, 0.00, "0.00"));
        defaults.put(AutoParticipantProfileType.AVERAGE_DOWN_BUYER, profileDefaults(0.20, 0.00, 0.55, 0.80, 0.05, 0.00, 0.00, 0.08, 0.00, 0.95, 1.05, 0.90, 1.80, 1.20, 0.75, 0.35, 0.05, "0.00"));
        defaults.put(AutoParticipantProfileType.STOP_LOSS_TRADER, profileDefaults(0.25, 0.35, 0.00, 0.00, 0.20, 0.00, 0.05, 0.18, 0.80, 0.00, 1.20, 1.25, 0.55, 0.95, 0.00, 0.10, 0.65, "0.00"));
        defaults.put(AutoParticipantProfileType.FOMO_BUYER, profileDefaults(0.35, 0.85, 0.00, 0.05, 0.65, 0.00, 0.35, 0.18, 0.05, 0.00, 1.00, 1.10, 0.90, 1.00, 0.05, 0.00, 0.25, "0.00"));
        defaults.put(AutoParticipantProfileType.PANIC_SELLER, profileDefaults(0.25, 0.22, 0.00, 0.22, 0.38, 0.00, 0.08, 0.16, 0.82, 0.00, 0.95, 1.05, 0.90, 0.95, 0.00, 0.00, 0.62, "0.00"));
        defaults.put(AutoParticipantProfileType.DIP_BUYER, profileDefaults(0.25, 0.00, 0.65, 0.35, 0.10, 0.00, 0.05, 0.15, 0.00, 0.90, 1.15, 1.05, 0.80, 1.00, 0.25, 0.25, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.PROFIT_LOCKER, profileDefaults(0.20, 0.35, 0.00, 0.10, 0.15, 0.00, 0.05, 0.20, 0.05, 0.00, 1.35, 1.25, 0.55, 0.85, 0.00, 0.95, 1.00, "0.00"));
        defaults.put(AutoParticipantProfileType.LIQUIDITY_AVOIDANT, profileDefaults(0.20, 0.10, 0.00, 0.35, 0.00, 0.00, 0.00, 0.05, 0.10, 0.00, 0.55, 0.55, 1.80, 0.60, 0.25, 0.10, 0.35, "0.00"));
        defaults.put(AutoParticipantProfileType.CASH_DEFENSIVE, profileDefaults(0.15, 0.05, 0.15, 0.50, 0.00, 0.00, 0.00, 0.04, 0.10, 0.10, 0.35, 0.45, 2.20, 0.35, 0.70, 0.20, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.WHALE, profileDefaults(0.30, 0.35, 0.00, 0.20, 0.25, 0.00, 0.20, 0.10, 0.05, 0.00, 1.20, 0.85, 1.20, 1.80, 0.05, 0.00, 0.30, "0.00"));
        defaults.put(AutoParticipantProfileType.SMALL_DIVERSIFIER, profileDefaults(0.25, 0.20, 0.10, 0.30, 0.10, 0.00, 0.05, 0.10, 0.00, 0.05, 1.20, 0.70, 1.20, 0.45, 0.25, 0.15, 0.25, "0.00"));
        defaults.put(AutoParticipantProfileType.OBSERVER, profileDefaults(0.15, 0.10, 0.00, 0.20, 0.00, 0.00, 0.00, 0.03, 0.00, 0.00, 0.30, 0.40, 2.20, 0.40, 0.10, 0.00, 0.10, "0.00"));
        defaults.replaceAll((profileType, value) -> value.withPricePressureSensitivity(
                defaultPricePressureSensitivity(profileType)
        ));
        return Map.copyOf(defaults);
    }

    private static double defaultPricePressureSensitivity(AutoParticipantProfileType profileType) {
        return switch (profileType) {
            case NEWS_REACTIVE, FOMO_BUYER, PANIC_SELLER -> 1.30;
            case MOMENTUM_FOLLOWER, HERD_FOLLOWER, STOP_LOSS_TRADER -> 1.20;
            case OVERCONFIDENT, DAY_TRADER -> 1.10;
            case SCALPER -> 1.05;
            case SWING_TRADER, PROFIT_LOCKER -> 1.00;
            case CONTRARIAN -> 0.95;
            case DIP_BUYER -> 0.90;
            case AVERAGE_DOWN_BUYER, WHALE -> 0.85;
            case LOSS_AVERSE -> 0.80;
            case VALUE_ANCHOR -> 0.70;
            case SMALL_DIVERSIFIER -> 0.65;
            case NOISE_TRADER, PAYDAY_ACCUMULATOR, DIVIDEND_REINVESTOR -> 0.60;
            case LONG_TERM_HOLDER -> 0.55;
            case LIQUIDITY_AVOIDANT, CASH_DEFENSIVE -> 0.45;
            case LIMIT_DOWN_TRAPPED -> 0.40;
            case MARKET_MAKER, OBSERVER -> 0.30;
        };
    }

    private static ProfileConfigDefaults profileDefaults(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double noiseWeight,
            double panicSellWeight,
            double dipBuyWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double orderTtlMultiplier,
            double quantityMultiplier,
            double holdingPatienceWeight,
            double deepLossHoldWeight,
            double profitTakingWeight,
            String recurringDepositAmount
    ) {
        return profileDefaults(
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
                new BigDecimal(recurringDepositAmount).compareTo(BigDecimal.ZERO) <= 0
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf(DEFAULT_RECURRING_DEPOSIT_INTERVAL_DAYS),
                RecurringCashIntervalUnit.DAY
        );
    }

    private static ProfileConfigDefaults profileDefaults(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double noiseWeight,
            double panicSellWeight,
            double dipBuyWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double orderTtlMultiplier,
            double quantityMultiplier,
            double holdingPatienceWeight,
            double deepLossHoldWeight,
            double profitTakingWeight,
            String recurringDepositAmount,
            Integer recurringDepositIntervalDays
    ) {
        return profileDefaults(
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
                BigDecimal.valueOf(recurringDepositIntervalDays),
                RecurringCashIntervalUnit.DAY
        );
    }

    private static ProfileConfigDefaults profileDefaults(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double noiseWeight,
            double panicSellWeight,
            double dipBuyWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double orderTtlMultiplier,
            double quantityMultiplier,
            double holdingPatienceWeight,
            double deepLossHoldWeight,
            double profitTakingWeight,
            String recurringDepositAmount,
            BigDecimal recurringDepositIntervalValue,
            RecurringCashIntervalUnit recurringDepositIntervalUnit
    ) {
        return new ProfileConfigDefaults(
                BigDecimal.valueOf(newsWeight),
                BigDecimal.valueOf(momentumWeight),
                BigDecimal.valueOf(contrarianWeight),
                BigDecimal.valueOf(lossAversionWeight),
                BigDecimal.valueOf(herdingWeight),
                BigDecimal.valueOf(marketMakingWeight),
                BigDecimal.valueOf(overconfidenceWeight),
                BigDecimal.valueOf(noiseWeight),
                BigDecimal.valueOf(panicSellWeight),
                BigDecimal.valueOf(dipBuyWeight),
                BigDecimal.valueOf(orderMultiplier),
                BigDecimal.valueOf(aggressionMultiplier),
                BigDecimal.ONE,
                BigDecimal.valueOf(orderTtlMultiplier),
                BigDecimal.valueOf(quantityMultiplier),
                BigDecimal.valueOf(holdingPatienceWeight),
                BigDecimal.valueOf(deepLossHoldWeight),
                BigDecimal.valueOf(profitTakingWeight),
                new BigDecimal(recurringDepositAmount),
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit
        );
    }
}
