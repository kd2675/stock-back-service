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
import java.util.List;

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
        SimulationClockRow row = findClock().orElseGet(this::createPausedClock);
        SimulationClockSnapshot snapshot = toSnapshot(row, LocalDateTime.now());
        return toResponse(snapshot, row.baseSimulationDate());
    }

    @Transactional
    public SimulationClockResponse jumpToSafePreset(SimulationClockJumpAction action) {
        if (action == null) {
            throw StockException.badRequest("Simulation clock jump action is required");
        }
        SimulationClockRow row = findClockForUpdate().orElseGet(this::createPausedClock);
        LocalDateTime now = LocalDateTime.now();
        SimulationClockSnapshot currentSnapshot = toSnapshot(row, now);
        LocalDateTime target = resolveSafeTarget(
                action,
                currentSnapshot,
                row.baseSimulationDate()
        );
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

    private SimulationClockResponse toResponse(
            SimulationClockSnapshot snapshot,
            LocalDate baseSimulationDate
    ) {
        LocalTime openTime = marketOpenTime();
        LocalTime closeTime = marketCloseTime();
        SimulationMarketSession marketSession = SimulationMarketSessions.resolve(snapshot.simulationDateTime(), openTime, closeTime);
        ClockControlState controlState = resolveClockControlState(
                snapshot,
                marketSession,
                baseSimulationDate
        );
        return new SimulationClockResponse(
                snapshot.simulationDate(),
                snapshot.simulationDateTime(),
                snapshot.simulationDayStart(),
                marketSession,
                openTime,
                closeTime,
                controlState.activeBusinessDate(),
                controlState.preparingBusinessDate(),
                controlState.advanceState().settlementCompleted(),
                controlState.advanceState().marketOpenReady(),
                controlState.availableJumpActions(),
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

    private LocalDateTime resolveSafeTarget(
            SimulationClockJumpAction action,
            SimulationClockSnapshot currentSnapshot,
            LocalDate baseSimulationDate
    ) {
        LocalDateTime currentDateTime = currentSnapshot.simulationDateTime();
        LocalTime openTime = marketOpenTime();
        LocalTime closeTime = marketCloseTime();
        SimulationMarketSession session = SimulationMarketSessions.resolve(currentDateTime, openTime, closeTime);
        ClockControlState controlState = resolveClockControlState(
                currentSnapshot,
                session,
                baseSimulationDate
        );
        if (!controlState.availableJumpActions().contains(action)) {
            throw unavailableJumpException(action, session, currentDateTime, controlState);
        }
        return switch (action) {
            case TODAY_MARKET_CLOSE -> currentDateTime.toLocalDate().atTime(closeTime);
            case NEXT_SIMULATION_DAY_START -> currentDateTime.toLocalDate().plusDays(1).atStartOfDay();
            case NEXT_MARKET_OPEN -> {
                LocalDate targetDate = session == SimulationMarketSession.AFTER_CLOSE
                        ? currentDateTime.toLocalDate().plusDays(1)
                        : currentDateTime.toLocalDate();
                yield targetDate.atTime(openTime);
            }
        };
    }

    private ClockControlState resolveClockControlState(
            SimulationClockSnapshot snapshot,
            SimulationMarketSession session,
            LocalDate baseSimulationDate
    ) {
        LocalDate fallbackActiveBusinessDate = fallbackActiveBusinessDate(
                snapshot.simulationDate(),
                session,
                baseSimulationDate
        );
        ClockControlRow row = jdbcClient.sql(
                        """
                        select case when exists (
                                   select 1
                                     from stock_order_book_instrument
                                    where enabled = true
                               ) then 1 else 0 end as market_enabled,
                               business_state.active_business_date,
                               business_state.preparing_business_date,
                               cycle.id as cycle_id,
                               cycle.cycle_kind,
                               cycle.phase,
                               cycle.status,
                               metric.close_cycle_id as metric_cycle_id,
                               metric.settlement_target_account_count,
                               metric.settled_account_count,
                               metric.settlement_missing_account_count
                          from (select 1 as anchor_id) anchor
                          left join stock_market_business_state business_state
                            on business_state.state_id = 'DEFAULT'
                          left join stock_post_close_cycle cycle
                            on cycle.business_date = coalesce(business_state.active_business_date, ?)
                           and cycle.scope_type = 'FULL_MARKET'
                           and cycle.scope_key = 'ALL'
                          left join stock_post_close_cycle_metric metric
                            on metric.close_cycle_id = cycle.id
                        """
                )
                .param(fallbackActiveBusinessDate)
                .query((rs, rowNum) -> new ClockControlRow(
                        rs.getBoolean("market_enabled"),
                        rs.getObject("active_business_date", LocalDate.class),
                        rs.getObject("preparing_business_date", LocalDate.class),
                        rs.getObject("cycle_id", Long.class),
                        rs.getString("cycle_kind"),
                        rs.getString("phase"),
                        rs.getString("status"),
                        rs.getObject("metric_cycle_id", Long.class),
                        rs.getLong("settlement_target_account_count"),
                        rs.getLong("settled_account_count"),
                        rs.getLong("settlement_missing_account_count")
                ))
                .single();
        LocalDate activeBusinessDate = row.activeBusinessDate() == null
                ? fallbackActiveBusinessDate
                : row.activeBusinessDate();
        PostCloseAdvanceState advanceState = resolveAdvanceState(
                row,
                snapshot,
                session,
                baseSimulationDate,
                activeBusinessDate
        );
        List<SimulationClockJumpAction> availableJumpActions = resolveAvailableJumpActions(
                snapshot.simulationDate(),
                session,
                baseSimulationDate,
                activeBusinessDate,
                row.preparingBusinessDate(),
                advanceState
        );
        return new ClockControlState(
                activeBusinessDate,
                row.preparingBusinessDate(),
                advanceState,
                availableJumpActions
        );
    }

    /**
     * Clock controls read only singleton/unique control rows. They must not rescan current accounts,
     * portfolio rows, orders, or executions because the one-second admin poll must remain independent
     * of trading volume and because the live account cohort is not the frozen close cohort.
     */
    private PostCloseAdvanceState resolveAdvanceState(
            ClockControlRow row,
            SimulationClockSnapshot snapshot,
            SimulationMarketSession session,
            LocalDate baseSimulationDate,
            LocalDate activeBusinessDate
    ) {
        if (!row.marketEnabled()) {
            return PostCloseAdvanceState.READY;
        }
        boolean initialPreOpen = session == SimulationMarketSession.PRE_OPEN
                && snapshot.simulationDate().equals(baseSimulationDate)
                && activeBusinessDate.equals(baseSimulationDate);
        boolean synchronizedRegularSession = session == SimulationMarketSession.REGULAR
                && activeBusinessDate.equals(snapshot.simulationDate())
                && row.preparingBusinessDate() == null;
        if (initialPreOpen || synchronizedRegularSession) {
            return PostCloseAdvanceState.READY;
        }
        if (row.cycleId() == null) {
            return PostCloseAdvanceState.PENDING;
        }
        if ("SKIPPED".equals(row.cycleKind())
                && "COMPLETED".equals(row.phase())
                && "COMPLETED".equals(row.status())) {
            return PostCloseAdvanceState.READY;
        }
        boolean reconciledSettlement = row.metricCycleId() != null
                && row.settlementMissingAccountCount() == 0L
                && row.settlementTargetAccountCount() == row.settledAccountCount();
        boolean legacyCompleted = row.metricCycleId() == null
                && "COMPLETED".equals(row.phase())
                && "COMPLETED".equals(row.status());
        boolean settlementCompleted = isSettledPhase(row.phase())
                && (reconciledSettlement || legacyCompleted);
        boolean marketOpenReady = settlementCompleted
                && ("READY_TO_OPEN".equals(row.phase()) || "COMPLETED".equals(row.phase()));
        return new PostCloseAdvanceState(settlementCompleted, marketOpenReady);
    }

    private List<SimulationClockJumpAction> resolveAvailableJumpActions(
            LocalDate rawSimulationDate,
            SimulationMarketSession session,
            LocalDate baseSimulationDate,
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            PostCloseAdvanceState advanceState
    ) {
        return switch (session) {
            case REGULAR -> activeBusinessDate.equals(rawSimulationDate)
                    && preparingBusinessDate == null
                    ? List.of(SimulationClockJumpAction.TODAY_MARKET_CLOSE)
                    : List.of();
            case AFTER_CLOSE -> {
                if (!activeBusinessDate.equals(rawSimulationDate)) {
                    yield List.of();
                }
                if (advanceState.marketOpenReady()) {
                    yield List.of(
                            SimulationClockJumpAction.NEXT_SIMULATION_DAY_START,
                            SimulationClockJumpAction.NEXT_MARKET_OPEN
                    );
                }
                yield advanceState.settlementCompleted()
                        ? List.of(SimulationClockJumpAction.NEXT_SIMULATION_DAY_START)
                        : List.of();
            }
            case PRE_OPEN -> {
                boolean initialSimulationDate = rawSimulationDate.equals(baseSimulationDate)
                        && activeBusinessDate.equals(baseSimulationDate);
                boolean preparedNextBusinessDate = activeBusinessDate.plusDays(1).equals(rawSimulationDate)
                        && rawSimulationDate.equals(preparingBusinessDate);
                yield advanceState.marketOpenReady()
                        && (initialSimulationDate || preparedNextBusinessDate)
                        ? List.of(SimulationClockJumpAction.NEXT_MARKET_OPEN)
                        : List.of();
            }
        };
    }

    private LocalDate fallbackActiveBusinessDate(
            LocalDate rawSimulationDate,
            SimulationMarketSession session,
            LocalDate baseSimulationDate
    ) {
        if (session != SimulationMarketSession.PRE_OPEN || rawSimulationDate.equals(baseSimulationDate)) {
            return rawSimulationDate;
        }
        LocalDate previousDate = rawSimulationDate.minusDays(1);
        return previousDate.isBefore(baseSimulationDate) ? baseSimulationDate : previousDate;
    }

    private StockException unavailableJumpException(
            SimulationClockJumpAction action,
            SimulationMarketSession session,
            LocalDateTime currentDateTime,
            ClockControlState controlState
    ) {
        return switch (action) {
            case TODAY_MARKET_CLOSE -> session != SimulationMarketSession.REGULAR
                    ? StockException.conflict("Today market close jump is only allowed during regular session")
                    : StockException.conflict(
                            "Active business date must match the raw simulation date before entering market close: active=%s, raw=%s"
                                    .formatted(controlState.activeBusinessDate(), currentDateTime.toLocalDate())
                    );
            case NEXT_SIMULATION_DAY_START -> session != SimulationMarketSession.AFTER_CLOSE
                    ? StockException.conflict("Next simulation day jump is only allowed after market close")
                    : StockException.conflict(
                            "Market close freeze, portfolio settlement, and active business-date alignment are required before moving to the next simulation day"
                    );
            case NEXT_MARKET_OPEN -> session == SimulationMarketSession.REGULAR
                    ? StockException.conflict("Next market open jump is not allowed during regular session")
                    : StockException.conflict(
                            "Overnight processing, market-open preparation, and prepared business-date alignment are required before moving to market open"
                    );
        };
    }

    private boolean isSettledPhase(String phase) {
        return switch (phase) {
            case "PORTFOLIO_SETTLED", "OVERNIGHT_CASH_APPLIED", "CORPORATE_CASH_APPLIED",
                    "REPORTS_AGGREGATED", "PREOPEN_SECURITY_TRANSFORMS_APPLIED",
                    "MARKET_DATA_PREPARED", "AUTO_MARKET_PREPARED", "READY_TO_OPEN", "COMPLETED" -> true;
            default -> false;
        };
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

    private record PostCloseAdvanceState(boolean settlementCompleted, boolean marketOpenReady) {
        private static final PostCloseAdvanceState PENDING = new PostCloseAdvanceState(false, false);
        private static final PostCloseAdvanceState READY = new PostCloseAdvanceState(true, true);
    }

    private record ClockControlState(
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            PostCloseAdvanceState advanceState,
            List<SimulationClockJumpAction> availableJumpActions
    ) {
    }

    private record ClockControlRow(
            boolean marketEnabled,
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            Long cycleId,
            String cycleKind,
            String phase,
            String status,
            Long metricCycleId,
            long settlementTargetAccountCount,
            long settledAccountCount,
            long settlementMissingAccountCount
    ) {
    }
}
