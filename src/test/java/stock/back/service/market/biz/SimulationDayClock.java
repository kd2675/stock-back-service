package stock.back.service.market.biz;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import web.common.core.simulation.SimulationClockSnapshots;

final class SimulationDayClock {

    static final int DAY_HOURS = 2;
    static final Duration DAY_DURATION = Duration.ofHours(DAY_HOURS);
    private static final int REAL_SECONDS_PER_SIMULATION_DAY = (int) DAY_DURATION.toSeconds();

    private SimulationDayClock() {
    }

    static LocalDateTime currentDayStart() {
        return dayStart(LocalDateTime.now());
    }

    static LocalDateTime dayStart(LocalDateTime value) {
        long elapsedRealSeconds = Duration.between(value.toLocalDate().atStartOfDay(), value).toSeconds();
        return SimulationClockSnapshots.calculate(
                LocalDate.of(2026, 1, 1),
                REAL_SECONDS_PER_SIMULATION_DAY,
                elapsedRealSeconds,
                false,
                null,
                value,
                30,
                value
        ).realDayStart();
    }
}
