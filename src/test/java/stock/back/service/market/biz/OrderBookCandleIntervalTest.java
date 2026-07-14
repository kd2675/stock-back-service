package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import web.common.core.simulation.SimulationClockSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookCandleIntervalTest {

    @Test
    void dayInterval_floorHistoricalDate_usesProvidedDate() {
        assertThat(OrderBookCandleInterval.DAY.floorHistoricalDate(LocalDateTime.of(2026, 6, 18, 15, 30)))
                .isEqualTo(LocalDateTime.of(2026, 6, 18, 0, 0));
    }

    @Test
    void weekInterval_floorHistoricalDate_usesMondayOfProvidedWeek() {
        assertThat(OrderBookCandleInterval.WEEK.floorHistoricalDate(LocalDateTime.of(2026, 6, 18, 15, 30)))
                .isEqualTo(LocalDateTime.of(2026, 6, 15, 0, 0));
    }

    @Test
    void dayInterval_usesSimulationDayBucket() {
        OrderBookCandleInterval interval = OrderBookCandleInterval.parse("1D");
        SimulationClockSnapshot clock = new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 3, 12, 0),
                LocalDateTime.of(2026, 7, 3, 0, 0),
                LocalDateTime.of(2026, 7, 1, 15, 37),
                LocalDateTime.of(2026, 7, 1, 14, 37),
                7200,
                true,
                false,
                3600,
                LocalDateTime.of(2026, 7, 1, 14, 37),
                LocalDateTime.of(2026, 7, 1, 15, 37)
        );

        assertThat(interval.floor(LocalDateTime.of(2026, 7, 3, 12, 0), clock))
                .isEqualTo(LocalDateTime.of(2026, 7, 3, 0, 0));
        assertThat(interval.minus(LocalDateTime.of(2026, 7, 3, 0, 0), 2, clock))
                .isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
        assertThat(interval.next(LocalDateTime.of(2026, 7, 3, 0, 0), clock))
                .isEqualTo(LocalDateTime.of(2026, 7, 4, 0, 0));
        assertThat(interval.bucketExpression("executed_at", 86_400))
                .contains("timestampdiff(second, ?, executed_at)")
                .contains("timestampadd(second")
                .contains("86400");
    }
}
