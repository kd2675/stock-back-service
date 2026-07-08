package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.SimulationClockJumpAction;
import stock.back.service.market.vo.SimulationClockResponse;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationClockSnapshots;
import web.common.core.simulation.SimulationMarketSession;
import web.common.core.simulation.SimulationMarketSessions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

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

    @Value("${stock.market-session.open-time:06:00}")
    private String openTimeValue;

    @Value("${stock.market-session.close-time:18:00}")
    private String closeTimeValue;

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
        return toResponse(snapshot);
    }

    @Transactional
    public SimulationClockResponse jumpToSafePreset(SimulationClockJumpAction action) {
        if (action == null) {
            throw StockException.badRequest("Simulation clock jump action is required");
        }
        SimulationClockRow row = findClockForUpdate().orElseGet(this::createPausedClock);
        LocalDateTime now = LocalDateTime.now();
        SimulationClockSnapshot currentSnapshot = toSnapshot(row, now);
        LocalDateTime target = resolveSafeTarget(action, currentSnapshot.simulationDateTime());
        if (!target.isAfter(currentSnapshot.simulationDateTime())) {
            throw StockException.conflict("Simulation clock can only move forward to a safe market boundary");
        }
        long targetAccumulatedRealSeconds = toAccumulatedRealSeconds(
                row.baseSimulationDate(),
                Math.max(1, row.realSecondsPerSimulationDay()),
                target
        );
        boolean keepRunning = row.running() && !currentSnapshot.stale();
        jdbcClient.sql(
                        """
                        update stock_simulation_clock
                           set accumulated_real_seconds = ?,
                               running = ?,
                               last_started_at = ?,
                               last_heartbeat_at = ?,
                               updated_at = ?
                         where clock_id = ?
                        """
                )
                .param(targetAccumulatedRealSeconds)
                .param(keepRunning)
                .param(keepRunning ? now : null)
                .param(now)
                .param(now)
                .param(row.clockId())
                .update();
        return currentResponse();
    }

    private SimulationClockResponse toResponse(SimulationClockSnapshot snapshot) {
        LocalTime openTime = marketOpenTime();
        LocalTime closeTime = marketCloseTime();
        SimulationMarketSession marketSession = SimulationMarketSessions.resolve(snapshot.simulationDateTime(), openTime, closeTime);
        return new SimulationClockResponse(
                snapshot.simulationDate(),
                snapshot.simulationDateTime(),
                snapshot.simulationDayStart(),
                marketSession,
                openTime,
                closeTime,
                isPostCloseProcessingCompleteForClockAdvance(snapshot, marketSession),
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

    private java.util.Optional<SimulationClockRow> findClockForUpdate() {
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
                         for update
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

    private LocalDateTime resolveSafeTarget(SimulationClockJumpAction action, LocalDateTime currentDateTime) {
        LocalTime openTime = marketOpenTime();
        LocalTime closeTime = marketCloseTime();
        SimulationMarketSession session = SimulationMarketSessions.resolve(currentDateTime, openTime, closeTime);
        return switch (action) {
            case TODAY_MARKET_CLOSE -> {
                if (session != SimulationMarketSession.REGULAR) {
                    throw StockException.conflict("Today market close jump is only allowed during regular session");
                }
                yield currentDateTime.toLocalDate().atTime(closeTime);
            }
            case NEXT_SIMULATION_DAY_START -> {
                if (session != SimulationMarketSession.AFTER_CLOSE) {
                    throw StockException.conflict("Next simulation day jump is only allowed after market close");
                }
                validatePostCloseProcessingComplete(currentDateTime.toLocalDate());
                yield currentDateTime.toLocalDate().plusDays(1).atStartOfDay();
            }
            case NEXT_MARKET_OPEN -> {
                if (session == SimulationMarketSession.REGULAR) {
                    throw StockException.conflict("Next market open jump is not allowed during regular session");
                }
                if (session == SimulationMarketSession.AFTER_CLOSE) {
                    validatePostCloseProcessingComplete(currentDateTime.toLocalDate());
                }
                if (session == SimulationMarketSession.PRE_OPEN) {
                    validatePreviousPostCloseProcessingComplete(currentDateTime);
                }
                LocalDate targetDate = session == SimulationMarketSession.AFTER_CLOSE
                        ? currentDateTime.toLocalDate().plusDays(1)
                        : currentDateTime.toLocalDate();
                yield targetDate.atTime(openTime);
            }
        };
    }

    private void validatePreviousPostCloseProcessingComplete(LocalDateTime currentDateTime) {
        LocalDate previousDate = currentDateTime.toLocalDate().minusDays(1);
        if (previousDate.isBefore(currentBaseSimulationDate())) {
            return;
        }
        validatePostCloseProcessingComplete(previousDate);
    }

    private void validatePostCloseProcessingComplete(LocalDate businessDate) {
        if (!isPostCloseProcessingComplete(businessDate)) {
            throw StockException.conflict("Market close post-processing must be completed before moving to the next simulation day");
        }
    }

    private boolean isPostCloseProcessingCompleteForClockAdvance(
            SimulationClockSnapshot snapshot,
            SimulationMarketSession session
    ) {
        if (session == SimulationMarketSession.AFTER_CLOSE) {
            return isPostCloseProcessingComplete(snapshot.simulationDate());
        }
        if (session == SimulationMarketSession.PRE_OPEN) {
            LocalDate previousDate = snapshot.simulationDate().minusDays(1);
            if (previousDate.isBefore(currentBaseSimulationDate())) {
                return true;
            }
            return isPostCloseProcessingComplete(previousDate);
        }
        return true;
    }

    /**
     * Clock-advance gate: do not rely on elapsed time. The next simulation boundary is safe only
     * after market-close rollover and portfolio settlement are both visible in the business DB.
     */
    private boolean isPostCloseProcessingComplete(LocalDate businessDate) {
        Long enabledInstrumentCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_order_book_instrument
                         where enabled = true
                        """
                )
                .query(Long.class)
                .single();
        if (enabledInstrumentCount == null || enabledInstrumentCount == 0) {
            return true;
        }
        Long completedRunCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_market_close_run
                         where business_date = ?
                           and status = 'COMPLETED'
                           and symbol is null
                        """
                )
                .param(businessDate)
                .query(Long.class)
                .single();
        if (completedRunCount == null || completedRunCount == 0) {
            return false;
        }
        return isPortfolioSettlementComplete(businessDate);
    }

    /**
     * Portfolio settlement is complete when every active participant account has a snapshot for
     * the business date. Listing supply accounts are operational inventory and are excluded.
     */
    private boolean isPortfolioSettlementComplete(LocalDate businessDate) {
        Long eligibleAccountCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_account
                         where status = 'ACTIVE'
                           and user_key is not null
                           and user_key not like 'stock-listing-%'
                        """
                )
                .query(Long.class)
                .single();
        if (eligibleAccountCount == null || eligibleAccountCount == 0) {
            return true;
        }
        Long snapshotAccountCount = jdbcClient.sql(
                        """
                        select count(distinct ps.account_id)
                          from portfolio_snapshot ps
                          join stock_account a on a.id = ps.account_id
                         where ps.snapshot_date = ?
                           and a.status = 'ACTIVE'
                           and a.user_key is not null
                           and a.user_key not like 'stock-listing-%'
                        """
                )
                .param(businessDate)
                .query(Long.class)
                .single();
        return snapshotAccountCount != null && snapshotAccountCount >= eligibleAccountCount;
    }

    private LocalDate currentBaseSimulationDate() {
        return findClock().orElseGet(this::createPausedClock).baseSimulationDate();
    }

    private long toAccumulatedRealSeconds(
            LocalDate baseSimulationDate,
            int secondsPerDay,
            LocalDateTime target
    ) {
        long days = ChronoUnit.DAYS.between(baseSimulationDate, target.toLocalDate());
        if (days < 0) {
            throw StockException.conflict("Simulation clock target is before base date");
        }
        long secondsInDay = target.toLocalTime().toSecondOfDay();
        long realSecondsInDay = Math.floorDiv(secondsInDay * secondsPerDay, 86_400L);
        return days * secondsPerDay + realSecondsInDay;
    }

    private LocalTime marketOpenTime() {
        return parseTime(openTimeValue, LocalTime.of(6, 0));
    }

    private LocalTime marketCloseTime() {
        return parseTime(closeTimeValue, LocalTime.of(18, 0));
    }

    private LocalTime parseTime(String value, LocalTime defaultValue) {
        return value == null || value.isBlank() ? defaultValue : LocalTime.parse(value);
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
