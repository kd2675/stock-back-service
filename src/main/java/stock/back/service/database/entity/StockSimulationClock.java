package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_simulation_clock")
public class StockSimulationClock {

    @Id
    @Column(name = "clock_id", nullable = false, length = 40)
    private String clockId;

    @Column(name = "base_simulation_date", nullable = false)
    private LocalDate baseSimulationDate;

    @Column(name = "real_seconds_per_simulation_day", nullable = false)
    private Integer realSecondsPerSimulationDay;

    @Column(name = "accumulated_real_seconds", nullable = false)
    private Long accumulatedRealSeconds;

    @Column(name = "running", nullable = false)
    private Boolean running;

    @Column(name = "last_started_at")
    private LocalDateTime lastStartedAt;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
