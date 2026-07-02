package stock.back.service.market.biz;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationDayClockTest {

    @Test
    void dayStart_alignsToCurrentTwoHourSimulationDayBucket() {
        assertThat(SimulationDayClock.dayStart(LocalDateTime.of(2026, 7, 1, 0, 1)))
                .isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
        assertThat(SimulationDayClock.dayStart(LocalDateTime.of(2026, 7, 1, 1, 59)))
                .isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
        assertThat(SimulationDayClock.dayStart(LocalDateTime.of(2026, 7, 1, 2, 0)))
                .isEqualTo(LocalDateTime.of(2026, 7, 1, 2, 0));
        assertThat(SimulationDayClock.dayStart(LocalDateTime.of(2026, 7, 1, 15, 37)))
                .isEqualTo(LocalDateTime.of(2026, 7, 1, 14, 0));
        assertThat(SimulationDayClock.dayStart(LocalDateTime.of(2026, 7, 1, 23, 59)))
                .isEqualTo(LocalDateTime.of(2026, 7, 1, 22, 0));
    }
}
