package stock.back.service.market.biz;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiquidityProviderPolicyPresetCatalogTest {

    private final LiquidityProviderPolicyPresetCatalog catalog =
            new LiquidityProviderPolicyPresetCatalog();

    @Test
    void resolveAll_scaledMillionShareMarket_returnsThreeRatioBoundedPresets() {
        var presets = catalog.resolveAll(
                1_000_000L,
                330_000L,
                new BigDecimal("80000000000.00"),
                new BigDecimal("0.700000"),
                new BigDecimal("0.250000")
        );

        assertThat(presets)
                .extracting(LiquidityProviderPolicyPresetCatalog.ResolvedPreset::code)
                .containsExactly("STABLE", "BALANCED", "ACTIVE");
        assertThat(presets)
                .allSatisfy(preset -> {
                    assertThat(preset.policy().maxOrderQuantity())
                            .isPositive()
                            .isLessThanOrEqualTo(preset.policy().inventoryBandQuantity());
                    assertThat(preset.policy().targetInventoryQuantity()
                            + preset.policy().inventoryBandQuantity())
                            .isLessThanOrEqualTo(1_000_000L);
                    assertThat(preset.policy().passiveOnly()).isTrue();
                    assertThat(preset.policy().minimumQuoteLifetimeSeconds())
                            .isLessThanOrEqualTo(preset.policy().orderTtlSeconds());
                    assertThat(preset.policy().quoteIntervalSeconds())
                            .isLessThanOrEqualTo(preset.policy().orderTtlSeconds());
                });
    }

    @Test
    void resolveAll_balancedPreset_keepsQuotesAcrossSchedulerCyclesAndCapsFloatTurnover() {
        var balanced = catalog.resolveAll(
                        1_000_000L,
                        330_000L,
                        new BigDecimal("80000000000.00"),
                        new BigDecimal("0.700000"),
                        new BigDecimal("0.250000")
                )
                .stream()
                .filter(LiquidityProviderPolicyPresetCatalog.ResolvedPreset::recommended)
                .findFirst()
                .orElseThrow();

        assertThat(balanced.code()).isEqualTo("BALANCED");
        assertThat(balanced.policy().referenceDailyVolume()).isEqualTo(30_000L);
        assertThat(balanced.policy().maxOrderQuantity()).isEqualTo(225L);
        assertThat(balanced.oneSideQuoteFloatRate()).isEqualByComparingTo("0.00022500");
        assertThat(balanced.dailyExecutionFloatRate()).isEqualByComparingTo("0.00540000");
        assertThat(balanced.dailySubmissionFloatRate()).isEqualByComparingTo("0.02700000");
        assertThat(balanced.policy().minimumQuoteLifetimeSeconds()).isEqualTo(600);
        assertThat(balanced.policy().orderTtlSeconds()).isEqualTo(1_800);
        assertThat(balanced.policy().quoteIntervalSeconds()).isEqualTo(300);
        assertThat(balanced.policy().dailyLossLimitAmount())
                .isEqualByComparingTo("3200000000.00");
    }

    @Test
    void resolveAllForReferenceVolume_keepsAdvDenominatorAcrossRiskStyles() {
        var presets = catalog.resolveAllForReferenceVolume(
                1_000_000L,
                900_000L,
                5_000L,
                new BigDecimal("1000000.00"),
                new BigDecimal("0.700000"),
                new BigDecimal("0.250000")
        );

        assertThat(presets)
                .allSatisfy(preset -> assertThat(
                        preset.policy().referenceDailyVolume()
                ).isEqualTo(900_000L));
        assertThat(presets)
                .extracting(preset -> BigDecimal.valueOf(
                        preset.policy().referenceDailyVolume()
                ).multiply(preset.policy().dailyExecutionParticipationRate()))
                .containsExactly(
                        new BigDecimal("72000.000000"),
                        new BigDecimal("162000.000000"),
                        new BigDecimal("270000.000000")
                );
    }
}
