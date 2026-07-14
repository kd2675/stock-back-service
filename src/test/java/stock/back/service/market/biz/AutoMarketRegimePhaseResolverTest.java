package stock.back.service.market.biz;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMarketRegimePhaseResolverTest {

    @ParameterizedTest
    @CsvSource({
            "2026-07-14T06:00:00, SLOT_0600",
            "2026-07-14T09:00:00, SLOT_0900",
            "2026-07-14T12:00:00, SLOT_1200",
            "2026-07-14T15:00:00, SLOT_1500"
    })
    void resolve_boundaryTime_returnsExpectedSlot(LocalDateTime dateTime, String expected) {
        assertThat(AutoMarketRegimePhaseResolver.resolve(dateTime)).isEqualTo(expected);
    }
}
