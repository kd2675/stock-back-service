package stock.back.service.market.biz;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class InstitutionPortfolioPolicyCatalogTest {

    @Test
    void policies_recommendedAumRates_matchCapacityPreset() {
        assertThat(InstitutionPortfolioPolicyCatalog.policies())
                .extracting(InstitutionPortfolioPolicyCatalog.Policy::recommendedAumRateOfMarketCap)
                .containsExactly(
                        new BigDecimal("0.050000"),
                        new BigDecimal("0.030000"),
                        new BigDecimal("0.020000"),
                        new BigDecimal("0.010000")
                );
    }

    @Test
    void policies_combinedAumRate_isElevenPercent() {
        BigDecimal combinedAumRate = InstitutionPortfolioPolicyCatalog.policies()
                .stream()
                .map(InstitutionPortfolioPolicyCatalog.Policy::recommendedAumRateOfMarketCap)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(combinedAumRate).isEqualByComparingTo("0.110000");
    }

    @Test
    void policies_exposeMeaningfulActiveDayCapacityWithoutForcedTurnover() {
        Map<String, InstitutionPortfolioPolicyCatalog.Policy> policies =
                InstitutionPortfolioPolicyCatalog.policies().stream()
                        .collect(Collectors.toMap(
                                InstitutionPortfolioPolicyCatalog.Policy::investmentStyle,
                                Function.identity()
                        ));

        assertThat(policies).hasSize(4);
        assertThat(policies.get("BALANCED_LONG_TERM").dailyTurnoverLimitRate())
                .isEqualByComparingTo("0.050000");
        assertThat(policies.get("VALUE_CONTRARIAN").dailyTurnoverLimitRate())
                .isEqualByComparingTo("0.100000");
        assertThat(policies.get("MOMENTUM").dailyTurnoverLimitRate())
                .isEqualByComparingTo("0.150000");
        assertThat(policies.get("ACTIVE_SHORT_TERM").dailyTurnoverLimitRate())
                .isEqualByComparingTo("0.200000");
    }

    @Test
    void policies_keepSymbolParticipationInsideThreeToTwentyPercentEnvelope() {
        assertThat(InstitutionPortfolioPolicyCatalog.policies())
                .allSatisfy(policy -> {
                    assertThat(policy.dailyParticipationRate())
                            .isGreaterThanOrEqualTo(new BigDecimal("0.030000"));
                    assertThat(policy.dailyParticipationRate())
                            .isLessThanOrEqualTo(new BigDecimal("0.200000"));
                    assertThat(policy.maxDecisionTurnoverRate())
                            .isLessThanOrEqualTo(policy.dailyTurnoverLimitRate());
                });
    }

    @Test
    void policies_recommendedAumCreatesMeaningfulCombinedRiskCapacity() {
        BigDecimal combinedAumRate = InstitutionPortfolioPolicyCatalog.policies()
                .stream()
                .map(InstitutionPortfolioPolicyCatalog.Policy::recommendedAumRateOfMarketCap)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal combinedDailyTurnoverCapacity =
                InstitutionPortfolioPolicyCatalog.policies()
                        .stream()
                        .map(policy -> policy.recommendedAumRateOfMarketCap()
                                .multiply(policy.dailyTurnoverLimitRate()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(combinedAumRate).isEqualByComparingTo("0.110000");
        assertThat(combinedDailyTurnoverCapacity)
                .isEqualByComparingTo("0.010500000000");
    }
}
