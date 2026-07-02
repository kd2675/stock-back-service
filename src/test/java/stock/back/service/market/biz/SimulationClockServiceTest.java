package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import web.common.core.simulation.SimulationClockSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationClockServiceTest {

    @Test
    void currentSnapshot_staleRunningClock_usesHeartbeatAsRealTimeBoundary() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        jdbcTemplate.update(
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
                values (?, ?, 7200, 7200, true, ?, ?, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0)
        );
        SimulationClockService service = service(jdbcTemplate);

        SimulationClockSnapshot snapshot = service.currentSnapshot();

        assertThat(snapshot.stale()).isTrue();
        assertThat(snapshot.simulationDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(snapshot.simulationDayStart()).isEqualTo(LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(snapshot.realDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 1, 11, 0));
        assertThat(snapshot.realDayStart()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
        assertThat(service.currentDayStart()).isEqualTo(LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(service.currentRealDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 12, 0));
    }

    private SimulationClockService service(JdbcTemplate jdbcTemplate) {
        SimulationClockService service = new SimulationClockService(JdbcClient.create(jdbcTemplate));
        ReflectionTestUtils.setField(service, "baseDateValue", "");
        ReflectionTestUtils.setField(service, "realSecondsPerSimulationDay", 7200);
        ReflectionTestUtils.setField(service, "staleAfterSeconds", 30L);
        return service;
    }

    private JdbcTemplate createJdbcTemplate() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:simulation_clock_back_%d;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(System.nanoTime()),
                "sa",
                ""
        ));
        jdbcTemplate.execute(
                """
                create table stock_simulation_clock (
                  clock_id varchar(40) not null primary key,
                  base_simulation_date date not null,
                  real_seconds_per_simulation_day int not null,
                  accumulated_real_seconds bigint not null default 0,
                  running boolean not null default false,
                  last_started_at timestamp null,
                  last_heartbeat_at timestamp null,
                  timezone varchar(50) not null default 'Asia/Seoul',
                  created_at timestamp not null,
                  updated_at timestamp not null
                )
                """
        );
        return jdbcTemplate;
    }
}
