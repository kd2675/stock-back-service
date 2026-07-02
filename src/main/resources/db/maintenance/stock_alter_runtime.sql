USE STOCK_SERVICE;

INSERT INTO stock_simulation_clock(
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
VALUES (
           'DEFAULT',
           '2026-07-03',
           7200,
           0,
           false,
           null,
           null,
           'Asia/Seoul',
           NOW(),
           NOW()
       )
    ON DUPLICATE KEY UPDATE
                         base_simulation_date = VALUES(base_simulation_date),
                         real_seconds_per_simulation_day = VALUES(real_seconds_per_simulation_day),
                         accumulated_real_seconds = 0,
                         running = false,
                         last_started_at = null,
                         last_heartbeat_at = null,
                         timezone = VALUES(timezone),
                         updated_at = VALUES(updated_at);