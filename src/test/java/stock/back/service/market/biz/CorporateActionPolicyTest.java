package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorporateActionPolicyTest {

    private static final LocalDate CURRENT_SIMULATION_DATE = LocalDate.of(2026, 7, 1);

    @Test
    void requirePaidInCapitalIncreaseDates_shareholderAllocationSameDayExRights_throwsBadRequest() {
        assertThatThrownBy(() -> CorporateActionPolicy.requirePaidInCapitalIncreaseDates(
                StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                CURRENT_SIMULATION_DATE,
                CURRENT_SIMULATION_DATE.plusDays(1),
                CURRENT_SIMULATION_DATE.plusDays(2),
                CURRENT_SIMULATION_DATE.plusDays(3),
                CURRENT_SIMULATION_DATE.plusDays(4),
                CURRENT_SIMULATION_DATE
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("must be after current simulation date");
    }

    @Test
    void requirePaidInCapitalIncreaseDates_shareholderRecordDateNotAfterExRights_throwsBadRequest() {
        LocalDate exRightsDate = CURRENT_SIMULATION_DATE.plusDays(1);

        assertThatThrownBy(() -> CorporateActionPolicy.requirePaidInCapitalIncreaseDates(
                StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                exRightsDate,
                exRightsDate,
                exRightsDate.plusDays(1),
                exRightsDate.plusDays(2),
                exRightsDate.plusDays(3),
                exRightsDate.plusDays(4),
                CURRENT_SIMULATION_DATE
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("ex-rights, record, subscription");
    }

    @Test
    void requirePaidInCapitalIncreaseDates_publicOfferingWithRecordDate_throwsBadRequest() {
        assertThatThrownBy(() -> CorporateActionPolicy.requirePaidInCapitalIncreaseDates(
                StockCapitalIncreaseOfferingType.PUBLIC_OFFERING,
                null,
                CURRENT_SIMULATION_DATE.plusDays(1),
                CURRENT_SIMULATION_DATE.plusDays(2),
                CURRENT_SIMULATION_DATE.plusDays(3),
                CURRENT_SIMULATION_DATE.plusDays(4),
                CURRENT_SIMULATION_DATE.plusDays(5),
                CURRENT_SIMULATION_DATE
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("does not use ex-rights or record dates");
    }

    @Test
    void defaultPaidInRecordDate_shareholderAllocation_returnsDayAfterExRights() {
        LocalDate exRightsDate = CURRENT_SIMULATION_DATE.plusDays(2);

        assertThat(CorporateActionPolicy.defaultPaidInRecordDate(
                StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                exRightsDate
        )).isEqualTo(exRightsDate.plusDays(1));
    }

    @Test
    void requireCashDividendDates_sameDayExDividend_throwsBadRequest() {
        assertThatThrownBy(() -> CorporateActionPolicy.requireCashDividendDates(
                CURRENT_SIMULATION_DATE,
                CURRENT_SIMULATION_DATE.plusDays(1),
                CURRENT_SIMULATION_DATE
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("must be after current simulation date");
    }

    @Test
    void requireFreeShareDistributionDates_sameDayExRights_throwsBadRequest() {
        assertThatThrownBy(() -> CorporateActionPolicy.requireFreeShareDistributionDates(
                CURRENT_SIMULATION_DATE,
                CURRENT_SIMULATION_DATE.plusDays(1),
                CURRENT_SIMULATION_DATE
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("must be after current simulation date");
    }

    @Test
    void calculateTheoreticalExRightsPrice_lowerIssuePrice_truncatesSubWon() {
        BigDecimal price = CorporateActionPolicy.calculateTheoreticalExRightsPrice(
                100000L,
                new BigDecimal("70000.00"),
                50000L,
                new BigDecimal("50000.00")
        );

        assertThat(price).isEqualByComparingTo(new BigDecimal("63333.00"));
    }

    @Test
    void calculateTheoreticalExRightsPrice_issuePriceNotLower_keepsBasePrice() {
        BigDecimal price = CorporateActionPolicy.calculateTheoreticalExRightsPrice(
                100000L,
                new BigDecimal("40000.00"),
                50000L,
                new BigDecimal("50000.00")
        );

        assertThat(price).isEqualByComparingTo(new BigDecimal("40000.00"));
    }

    @Test
    void calculateTheoreticalExRightsPrice_shareSumExceedsLongRange_doesNotOverflow() {
        BigDecimal price = CorporateActionPolicy.calculateTheoreticalExRightsPrice(
                Long.MAX_VALUE,
                new BigDecimal("100.00"),
                1L,
                new BigDecimal("50.00")
        );

        assertThat(price).isEqualByComparingTo(new BigDecimal("99"));
    }

    @Test
    void calculateTheoreticalFreeSharePrice_shareSumExceedsLongRange_doesNotOverflow() {
        BigDecimal price = CorporateActionPolicy.calculateTheoreticalFreeSharePrice(
                Long.MAX_VALUE,
                new BigDecimal("100.00"),
                1L
        );

        assertThat(price).isEqualByComparingTo(new BigDecimal("99"));
    }

    @Test
    void calculateTheoreticalFreeSharePrice_fractionalWon_truncatesSubWon() {
        BigDecimal price = CorporateActionPolicy.calculateTheoreticalFreeSharePrice(
                100000L,
                new BigDecimal("70000.00"),
                10000L
        );

        assertThat(price).isEqualByComparingTo(new BigDecimal("63636.00"));
    }
}
