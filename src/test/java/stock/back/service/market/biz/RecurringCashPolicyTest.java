package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.RecurringCashIntervalUnit;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurringCashPolicyTest {

    @Test
    void normalizeIntervalValue_positiveAmountWithoutInterval_throwsBadRequest() {
        assertThatThrownBy(() -> RecurringCashPolicy.normalizeIntervalValue(null, BigDecimal.ONE))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Recurring cash interval value is required");
    }

    @Test
    void normalizeIntervalUnit_positiveAmountWithoutUnit_throwsBadRequest() {
        assertThatThrownBy(() -> RecurringCashPolicy.normalizeIntervalUnit(" ", BigDecimal.ONE))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Recurring cash interval unit is required");
    }

    @Test
    void normalizeIntervalUnit_positiveAmountWithSubDayUnit_throwsBadRequest() {
        assertThatThrownBy(() -> RecurringCashPolicy.normalizeIntervalUnit("HOUR", BigDecimal.ONE))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("DAY, MONTH, or YEAR");
    }

    @Test
    void normalizeIntervalUnit_disabledAmountWithExplicitSubDayUnit_throwsBadRequest() {
        assertThatThrownBy(() -> RecurringCashPolicy.normalizeIntervalUnit("HOUR", BigDecimal.ZERO))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("DAY, MONTH, or YEAR");
    }

    @Test
    void normalizeIntervalUnit_positiveAmountWithDayOrLongerUnit_returnsUnit() {
        assertThat(RecurringCashPolicy.normalizeIntervalUnit("MONTH", BigDecimal.ONE))
                .isEqualTo(RecurringCashIntervalUnit.MONTH);
    }

    @Test
    void normalizeInterval_zeroAmountWithoutInterval_usesStoppedScheduleDefaults() {
        assertThat(RecurringCashPolicy.normalizeIntervalValue(null, BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(RecurringCashPolicy.normalizeIntervalUnit(null, BigDecimal.ZERO))
                .isNull();
    }

    @Test
    void intervalDays_nonDayUnit_returnsCompatibilityFallback() {
        assertThat(RecurringCashPolicy.intervalDays(BigDecimal.ONE, RecurringCashIntervalUnit.HOUR))
                .isEqualTo(1);
    }

    @Test
    void intervalDays_disabledDaySchedule_preservesLegacyDatabaseMinimum() {
        assertThat(RecurringCashPolicy.intervalDays(BigDecimal.ZERO, RecurringCashIntervalUnit.DAY))
                .isEqualTo(1);
    }
}
