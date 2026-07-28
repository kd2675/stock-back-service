package stock.back.service.market.biz;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

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
}
