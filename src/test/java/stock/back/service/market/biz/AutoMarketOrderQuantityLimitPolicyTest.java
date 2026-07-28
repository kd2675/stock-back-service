package stock.back.service.market.biz;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoMarketOrderQuantityLimitPolicyTest {

    @Test
    void recommendedMaxOrderQuantity_newSevenSymbolPlan_matchesRiskCeilings() {
        assertThat(recommended("3000", 5_000_000L)).isEqualTo(1_000);
        assertThat(recommended("15000", 1_500_000L)).isEqualTo(300);
        assertThat(recommended("40000", 625_000L)).isEqualTo(125);
        assertThat(recommended("120000", 200_000L)).isEqualTo(40);
    }

    @Test
    void recommendedMaxOrderQuantity_notionalLimitIsLower_usesNotionalLimit() {
        assertThat(recommended("69500", 1_000_000L)).isEqualTo(71);
    }

    @Test
    void recommendedMaxOrderQuantity_calculatedLimitBelowOne_keepsOneShare() {
        assertThat(recommended("6000000", 1L)).isOne();
    }

    @Test
    void recommendedMaxOrderQuantity_invalidInputs_rejects() {
        assertThatThrownBy(() -> recommended("0", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recommended("1000", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private int recommended(String price, long tradableShares) {
        return AutoMarketOrderQuantityLimitPolicy.recommendedMaxOrderQuantity(
                new BigDecimal(price),
                tradableShares
        );
    }
}
