package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.market.vo.SimulationClockResponse;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationClockSnapshots;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SimulationClockService {

    static final String DEFAULT_CLOCK_ID = "DEFAULT";

    private final JdbcClient jdbcClient;

    @Value("${stock.simulation-clock.base-date:}")
    private String baseDateValue;

    @Value("${stock.simulation-clock.real-seconds-per-day:7200}")
    private int realSecondsPerSimulationDay;

    @Value("${stock.simulation-clock.stale-after-seconds:30}")
    private long staleAfterSeconds;

    @Transactional
    public SimulationClockSnapshot currentSnapshot() {
        SimulationClockRow row = findClock().orElseGet(this::createPausedClock);
        return toSnapshot(row, LocalDateTime.now());
    }

    public LocalDate currentDate() {
        return currentSnapshot().simulationDate();
    }

    public LocalDateTime currentMarketDayStart() {
        return currentSnapshot().simulationDayStart();
    }

    public LocalDateTime currentMarketDateTime() {
        return currentSnapshot().simulationDateTime();
    }

    public LocalDateTime currentDayStart() {
        return currentMarketDayStart();
    }

    public LocalDateTime currentRealDateTime() {
        return currentMarketDateTime();
    }

    public SimulationClockResponse currentResponse() {
        SimulationClockSnapshot snapshot = currentSnapshot();
        return new SimulationClockResponse(
                snapshot.simulationDate(),
                snapshot.simulationDateTime(),
                snapshot.simulationDayStart(),
                snapshot.realSecondsPerSimulationDay(),
                snapshot.running(),
                snapshot.stale(),
                snapshot.accumulatedRealSeconds(),
                snapshot.lastStartedAt(),
                snapshot.lastHeartbeatAt()
        );
    }

    private java.util.Optional<SimulationClockRow> findClock() {
        return jdbcClient.sql(
                        """
                        select clock_id,
                               base_simulation_date,
                               real_seconds_per_simulation_day,
                               accumulated_real_seconds,
                               running,
                               last_started_at,
                               last_heartbeat_at
                          from stock_simulation_clock
                         where clock_id = ?
                        """
                )
                .param(DEFAULT_CLOCK_ID)
                .query((rs, rowNum) -> new SimulationClockRow(
                        rs.getString("clock_id"),
                        rs.getObject("base_simulation_date", LocalDate.class),
                        rs.getInt("real_seconds_per_simulation_day"),
                        rs.getLong("accumulated_real_seconds"),
                        rs.getBoolean("running"),
                        rs.getObject("last_started_at", LocalDateTime.class),
                        rs.getObject("last_heartbeat_at", LocalDateTime.class)
                ))
                .optional();
    }

    private SimulationClockRow createPausedClock() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate baseSimulationDate = initialBaseDate();
        try {
            jdbcClient.sql(
                            """
                            insert into stock_simulation_clock(
                                clock_id,
                                base_simulation_date,
                                real_seconds_per_simulation_day,
                                accumulated_real_seconds,
                                running,
                                last_started_at,
                                last_heartbeat_at,
                                timezone,
                                created_at,
                                updated_at
                            )
                            values (?, ?, ?, 0, false, null, null, 'Asia/Seoul', ?, ?)
                            """
                    )
                    .param(DEFAULT_CLOCK_ID)
                    .param(baseSimulationDate)
                    .param(realSecondsPerSimulationDay)
                    .param(now)
                    .param(now)
                    .update();
        } catch (DuplicateKeyException ignored) {
            return findClock().orElseThrow();
        }
        return new SimulationClockRow(
                DEFAULT_CLOCK_ID,
                baseSimulationDate,
                realSecondsPerSimulationDay,
                0,
                false,
                null,
                null
        );
    }

    private LocalDate initialBaseDate() {
        return baseDateValue == null || baseDateValue.isBlank()
                ? LocalDate.now()
                : LocalDate.parse(baseDateValue);
    }

    private SimulationClockSnapshot toSnapshot(SimulationClockRow row, LocalDateTime now) {
        return SimulationClockSnapshots.calculate(
                row.baseSimulationDate(),
                row.realSecondsPerSimulationDay(),
                row.accumulatedRealSeconds(),
                row.running(),
                row.lastStartedAt(),
                row.lastHeartbeatAt(),
                staleAfterSeconds,
                now
        );
    }

    private record SimulationClockRow(
            String clockId,
            LocalDate baseSimulationDate,
            int realSecondsPerSimulationDay,
            long accumulatedRealSeconds,
            boolean running,
            LocalDateTime lastStartedAt,
            LocalDateTime lastHeartbeatAt
    ) {
    }
}
