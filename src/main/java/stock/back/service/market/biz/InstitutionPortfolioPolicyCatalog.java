package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import stock.back.service.common.exception.StockException;

final class InstitutionPortfolioPolicyCatalog {

    private static final List<Policy> POLICIES = List.of(
            new Policy(
                    "BALANCED_LONG_TERM", "연기금·저회전 균형형",
                    decimal("0.600000"), decimal("0.500000"), decimal("0.700000"),
                    decimal("0.800000"), decimal("0.015000"), decimal("0.020000"),
                    decimal("0.005000"), decimal("0.002000"),
                    decimal("0.005000"), decimal("0.001000"), 120,
                    decimal("0.020000"), decimal("0.020000"),
                    decimal("0.020000"), decimal("0.020000"), decimal("0.010000")
            ),
            new Policy(
                    "VALUE_CONTRARIAN", "가치·역추세형",
                    decimal("0.650000"), decimal("0.450000"), decimal("0.800000"),
                    decimal("0.750000"), decimal("0.020000"), decimal("0.020000"),
                    decimal("0.005000"), decimal("0.002000"),
                    decimal("0.010000"), decimal("0.002000"), 90,
                    decimal("-0.120000"), decimal("-0.020000"),
                    decimal("0.250000"), decimal("0.080000"), decimal("0.020000")
            ),
            new Policy(
                    "MOMENTUM", "모멘텀형",
                    decimal("0.600000"), decimal("0.350000"), decimal("0.850000"),
                    decimal("0.600000"), decimal("0.025000"), decimal("0.025000"),
                    decimal("0.006000"), decimal("0.002500"),
                    decimal("0.015000"), decimal("0.003000"), 60,
                    decimal("0.150000"), decimal("0.250000"),
                    decimal("-0.030000"), decimal("0.100000"), decimal("0.020000")
            ),
            new Policy(
                    "ACTIVE_SHORT_TERM", "단기 적극운용형",
                    decimal("0.550000"), decimal("0.250000"), decimal("0.850000"),
                    decimal("0.400000"), decimal("0.030000"), decimal("0.030000"),
                    decimal("0.008000"), decimal("0.003000"),
                    decimal("0.020000"), decimal("0.004000"), 30,
                    decimal("0.200000"), decimal("0.200000"),
                    decimal("0.000000"), decimal("0.120000"), decimal("0.030000")
            )
    );

    private InstitutionPortfolioPolicyCatalog() {
    }

    static List<Policy> policies() {
        return POLICIES;
    }

    static Policy require(String investmentStyle) {
        String normalized = investmentStyle == null
                ? ""
                : investmentStyle.trim().toUpperCase(Locale.ROOT);
        return POLICIES.stream()
                .filter(policy -> policy.investmentStyle().equals(normalized))
                .findFirst()
                .orElseThrow(() -> StockException.badRequest(
                        "Unsupported institution investment style: " + normalized
                ));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    record Policy(
            String investmentStyle,
            String recommendationLabel,
            BigDecimal baseStockAllocationRate,
            BigDecimal minStockAllocationRate,
            BigDecimal maxStockAllocationRate,
            BigDecimal primaryRegimeWeight,
            BigDecimal assetPreferenceSensitivity,
            BigDecimal volatilitySensitivity,
            BigDecimal entryThresholdRate,
            BigDecimal exitThresholdRate,
            BigDecimal dailyTurnoverLimitRate,
            BigDecimal maxDecisionTurnoverRate,
            int decisionIntervalMinutes,
            BigDecimal pricePressureSensitivity,
            BigDecimal momentumSensitivity,
            BigDecimal valueSensitivity,
            BigDecimal reportSensitivity,
            BigDecimal dailyParticipationRate
    ) {
    }
}
