package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.AutoParticipantV3OperationsResponse;
import stock.back.service.market.vo.AutoParticipantV3RuntimeRequest;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
@Slf4j
public class AutoParticipantV3OperationsService {

    private static final int ACCOUNT_STATE_LIMIT = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;

    public AutoParticipantV3OperationsService(
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true, transactionManager = "pubJdbcTransactionManager")
    public AutoParticipantV3OperationsResponse getOperations() {
        LocalDate tradeDate = simulationClockService.currentSnapshot().simulationDate();
        List<AutoParticipantV3OperationsResponse.PolicyRevision> policies =
                jdbcClient.sql(
                                """
                                select policy_version, status, effective_trade_date,
                                       runtime_enabled, policy_json, created_by,
                                       created_at, activated_at, retired_at,
                                       runtime_change_reason, runtime_changed_by,
                                       runtime_changed_at
                                  from stock_auto_participant_policy_revision
                                 where status in ('ACTIVE', 'SCHEDULED')
                                 order by case status when 'ACTIVE' then 0 else 1 end,
                                          effective_trade_date,
                                          policy_version
                                """
                        )
                        .query((rs, rowNum) ->
                                new AutoParticipantV3OperationsResponse.PolicyRevision(
                                        rs.getLong("policy_version"),
                                        rs.getString("status"),
                                        rs.getObject("effective_trade_date", LocalDate.class),
                                        rs.getBoolean("runtime_enabled"),
                                        rs.getString("policy_json"),
                                        rs.getString("created_by"),
                                        rs.getObject("created_at", LocalDateTime.class),
                                        rs.getObject("activated_at", LocalDateTime.class),
                                        rs.getObject("retired_at", LocalDateTime.class),
                                        rs.getString("runtime_change_reason"),
                                        rs.getString("runtime_changed_by"),
                                        rs.getObject("runtime_changed_at", LocalDateTime.class)
                                ))
                        .list();
        List<AutoParticipantV3OperationsResponse.DailyAccountState> states =
                findDailyStates(tradeDate);
        AutoParticipantV3OperationsResponse.DailySummary summary = summarize(states);
        long incompleteLiquidationPlanCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_auto_participant_liquidation_plan
                         where simulation_trade_date = :tradeDate
                           and status in ('PENDING', 'SUBMITTED', 'INCOMPLETE')
                        """
                )
                .param("tradeDate", tradeDate)
                .query(Long.class)
                .single();
        return new AutoParticipantV3OperationsResponse(
                tradeDate,
                policies,
                summary,
                states,
                incompleteLiquidationPlanCount
        );
    }

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public void updateRuntime(
            AutoParticipantV3RuntimeRequest request,
            String changedBy
    ) {
        if (request == null) {
            throw StockException.badRequest("V3 runtime update is required");
        }
        String reason = request.changeReason() == null
                ? ""
                : request.changeReason().trim();
        if (reason.isEmpty() || reason.length() > 200) {
            throw StockException.badRequest(
                    "V3 runtime change reason must contain 1 to 200 characters"
            );
        }
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        String actor = normalizeActor(changedBy);
        int updated = jdbcTemplate.update(
                """
                update stock_auto_participant_policy_revision
                   set runtime_enabled = ?,
                       runtime_change_reason = ?,
                       runtime_changed_by = ?,
                       runtime_changed_at = ?
                 where status = 'ACTIVE'
                """,
                request.runtimeEnabled(),
                reason,
                actor,
                clock.simulationDateTime()
        );
        if (updated != 1) {
            throw StockException.conflict(
                    "Exactly one active V3 policy is required for runtime control"
            );
        }
        log.warn(
                "Auto participant V3 runtime changed: enabled={}, changedBy={}, reason={}, simulationAt={}",
                request.runtimeEnabled(),
                actor,
                reason,
                clock.simulationDateTime()
        );
    }

    private List<AutoParticipantV3OperationsResponse.DailyAccountState> findDailyStates(
            LocalDate tradeDate
    ) {
        return jdbcClient.sql(
                        """
                        select state.account_id, state.user_key, state.profile_type,
                               state.policy_version, state.activity_state,
                               state.activity_session, state.event_sequence,
                               state.fatigue_score, state.submitted_order_count,
                               state.submitted_notional,
                               state.observed_execution_count,
                               state.observed_execution_notional,
                               state.observed_cancel_count,
                               state.last_result_reason, state.last_hold_reason,
                               schedule.next_attention_at, schedule.next_guard_at,
                               schedule.next_run_at, state.updated_at
                          from stock_auto_participant_daily_behavior_state state
                          left join stock_auto_participant_order_schedule schedule
                            on schedule.account_id = state.account_id
                           and schedule.simulation_trade_date =
                               state.simulation_trade_date
                         where state.simulation_trade_date = :tradeDate
                         order by state.account_id
                         limit :limit
                        """
                )
                .param("tradeDate", tradeDate)
                .param("limit", ACCOUNT_STATE_LIMIT)
                .query((rs, rowNum) ->
                        new AutoParticipantV3OperationsResponse.DailyAccountState(
                                rs.getLong("account_id"),
                                rs.getString("user_key"),
                                rs.getString("profile_type"),
                                rs.getLong("policy_version"),
                                rs.getString("activity_state"),
                                rs.getString("activity_session"),
                                rs.getLong("event_sequence"),
                                rs.getBigDecimal("fatigue_score"),
                                rs.getLong("submitted_order_count"),
                                rs.getBigDecimal("submitted_notional"),
                                rs.getLong("observed_execution_count"),
                                rs.getBigDecimal("observed_execution_notional"),
                                rs.getLong("observed_cancel_count"),
                                rs.getString("last_result_reason"),
                                rs.getString("last_hold_reason"),
                                rs.getObject("next_attention_at", LocalDateTime.class),
                                rs.getObject("next_guard_at", LocalDateTime.class),
                                rs.getObject("next_run_at", LocalDateTime.class),
                                rs.getObject("updated_at", LocalDateTime.class)
                        ))
                .list();
    }

    private AutoParticipantV3OperationsResponse.DailySummary summarize(
            List<AutoParticipantV3OperationsResponse.DailyAccountState> states
    ) {
        long offlineCount = states.stream()
                .filter(state -> "OFFLINE".equals(state.activityState()))
                .count();
        long submittedCount = states.stream()
                .mapToLong(AutoParticipantV3OperationsResponse.DailyAccountState::submittedOrderCount)
                .sum();
        BigDecimal submittedNotional = states.stream()
                .map(AutoParticipantV3OperationsResponse.DailyAccountState::submittedNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long executionCount = states.stream()
                .mapToLong(AutoParticipantV3OperationsResponse.DailyAccountState::observedExecutionCount)
                .sum();
        BigDecimal executionNotional = states.stream()
                .map(AutoParticipantV3OperationsResponse.DailyAccountState::observedExecutionNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long cancelCount = states.stream()
                .mapToLong(AutoParticipantV3OperationsResponse.DailyAccountState::observedCancelCount)
                .sum();
        BigDecimal fatigueTotal = states.stream()
                .map(AutoParticipantV3OperationsResponse.DailyAccountState::fatigueScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageFatigue = states.isEmpty()
                ? BigDecimal.ZERO.setScale(6)
                : fatigueTotal.divide(
                        BigDecimal.valueOf(states.size()),
                        6,
                        RoundingMode.HALF_UP
                );
        return new AutoParticipantV3OperationsResponse.DailySummary(
                states.size(),
                offlineCount,
                submittedCount,
                submittedNotional,
                executionCount,
                executionNotional,
                cancelCount,
                averageFatigue
        );
    }

    private String normalizeActor(String changedBy) {
        return changedBy == null || changedBy.isBlank()
                ? "UNKNOWN_ADMIN"
                : changedBy.trim();
    }
}
