package stock.back.service.market.biz;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.market.vo.EodOperationsOverviewResponse;
import web.common.core.simulation.SimulationClockSnapshots;

@Service
@RequiredArgsConstructor
public class EodOperationsOverviewService {

    private static final String CYCLE_SELECT = """
            select cycle.id,
                   cycle.business_date,
                   cycle.cycle_kind,
                   cycle.skip_reason,
                   cycle.phase,
                   cycle.status,
                   cycle.phase_revision,
                   cycle.attempt_count,
                   cycle.close_run_id,
                   cycle.settlement_eligible_at,
                   cycle.owner_id,
                   cycle.lease_until,
                   cycle.next_retry_at,
                   cycle.started_at,
                   cycle.completed_at,
                   cycle.last_error_code,
                   cycle.last_error_message,
                   cycle.build_version,
                   cycle.schema_version,
                   cycle.created_at,
                   cycle.updated_at,
                   close_run.status as close_run_status,
                   close_run.closed_at,
                   close_run.completed_at as close_run_completed_at
              from stock_post_close_cycle cycle
              left join stock_market_close_run close_run on close_run.id = cycle.close_run_id
             where cycle.scope_type = 'FULL_MARKET'
               and cycle.scope_key = 'ALL'
            """;

    private final JdbcClient jdbcClient;

    @Value("${stock.simulation-clock.stale-after-seconds:30}")
    private long simulationClockStaleAfterSeconds = 30L;

    /**
     * The polling endpoint intentionally reads only singleton/cycle/attempt/metric rows and
     * the small enabled-symbol configuration. It never scans stock_order or stock_execution.
     */
    @Transactional(readOnly = true)
    public EodOperationsOverviewResponse overview() {
        EodOperationsOverviewResponse.BusinessState businessState = findBusinessState().orElse(null);
        EodOperationsOverviewResponse.MarketState marketState = findMarketState();
        EodOperationsOverviewResponse.Cycle cycle = findOldestIncompleteCycle()
                .or(this::findLatestCycle)
                .orElse(null);
        if (cycle == null) {
            return new EodOperationsOverviewResponse(
                    LocalDateTime.now(),
                    businessState,
                    marketState,
                    null,
                    null,
                    List.of(),
                    null,
                    null
            );
        }
        return new EodOperationsOverviewResponse(
                LocalDateTime.now(),
                businessState,
                marketState,
                cycle,
                findMetrics(cycle.id()).orElse(null),
                findReadinessChecks(cycle.id()),
                findLatestAttempt(cycle.id()).orElse(null),
                findLatestSignal(cycle.id()).orElse(null)
        );
    }

    private Optional<EodOperationsOverviewResponse.BusinessState> findBusinessState() {
        return jdbcClient.sql(
                        """
                        select state.active_business_date,
                               state.preparing_business_date,
                               state.raw_simulation_date,
                               state.version,
                               state.updated_at,
                               clock.base_simulation_date,
                               clock.real_seconds_per_simulation_day,
                               clock.accumulated_real_seconds,
                               clock.running,
                               clock.last_started_at,
                               clock.last_heartbeat_at
                          from stock_market_business_state state
                          left join stock_simulation_clock clock
                            on clock.clock_id = 'DEFAULT'
                         where state.state_id = 'DEFAULT'
                        """
                )
                .query((rs, rowNum) -> new EodOperationsOverviewResponse.BusinessState(
                        rs.getObject("active_business_date", LocalDate.class),
                        rs.getObject("preparing_business_date", LocalDate.class),
                        rs.getObject("raw_simulation_date", LocalDate.class),
                        rawSimulationDateTime(rs),
                        rs.getLong("version"),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .optional();
    }

    private LocalDateTime rawSimulationDateTime(ResultSet rs) throws SQLException {
        LocalDate baseSimulationDate = rs.getObject("base_simulation_date", LocalDate.class);
        if (baseSimulationDate == null) {
            return null;
        }
        return SimulationClockSnapshots.calculate(
                baseSimulationDate,
                rs.getInt("real_seconds_per_simulation_day"),
                rs.getLong("accumulated_real_seconds"),
                rs.getBoolean("running"),
                rs.getObject("last_started_at", LocalDateTime.class),
                rs.getObject("last_heartbeat_at", LocalDateTime.class),
                simulationClockStaleAfterSeconds,
                LocalDateTime.now()
        ).simulationDateTime();
    }

    private EodOperationsOverviewResponse.MarketState findMarketState() {
        return jdbcClient.sql(
                        """
                        select count(*) as enabled_symbol_count,
                               coalesce(sum(case when market_status = 'OPEN' then 1 else 0 end), 0)
                                   as open_symbol_count
                          from (
                               select market_status
                                 from stock_order_book_market_config
                                where enabled = true
                               union all
                               select market_status
                                 from stock_virtual_market_config
                                where enabled = true
                          ) enabled_markets
                        """
                )
                .query((rs, rowNum) -> {
                    int enabled = rs.getInt("enabled_symbol_count");
                    int open = rs.getInt("open_symbol_count");
                    return new EodOperationsOverviewResponse.MarketState(enabled, open, open > 0);
                })
                .single();
    }

    private Optional<EodOperationsOverviewResponse.Cycle> findOldestIncompleteCycle() {
        return jdbcClient.sql(CYCLE_SELECT + """
                       and cycle.status in ('PENDING', 'RUNNING', 'DEFERRED', 'FAILED')
                     order by cycle.business_date, cycle.id
                     limit 1
                    """)
                .query(this::mapCycle)
                .optional();
    }

    private Optional<EodOperationsOverviewResponse.Cycle> findLatestCycle() {
        return jdbcClient.sql(CYCLE_SELECT + """
                     order by cycle.business_date desc, cycle.id desc
                     limit 1
                    """)
                .query(this::mapCycle)
                .optional();
    }

    private EodOperationsOverviewResponse.Cycle mapCycle(ResultSet rs, int rowNum) throws SQLException {
        return new EodOperationsOverviewResponse.Cycle(
                rs.getLong("id"),
                rs.getObject("business_date", LocalDate.class),
                rs.getString("cycle_kind"),
                rs.getString("skip_reason"),
                rs.getString("phase"),
                rs.getString("status"),
                rs.getInt("phase_revision"),
                rs.getInt("attempt_count"),
                nullableLong(rs, "close_run_id"),
                rs.getObject("settlement_eligible_at", LocalDateTime.class),
                rs.getString("owner_id"),
                rs.getObject("lease_until", LocalDateTime.class),
                rs.getObject("next_retry_at", LocalDateTime.class),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getString("last_error_code"),
                rs.getString("last_error_message"),
                rs.getString("build_version"),
                rs.getString("schema_version"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getString("close_run_status"),
                rs.getObject("closed_at", LocalDateTime.class),
                rs.getObject("close_run_completed_at", LocalDateTime.class)
        );
    }

    private Optional<EodOperationsOverviewResponse.CycleMetrics> findMetrics(long closeCycleId) {
        return jdbcClient.sql(
                        """
                        select captured_open_order_count, cancelled_order_count,
                               released_buy_cash, released_sell_quantity,
                               settlement_target_account_count, account_snapshot_count,
                               holding_snapshot_count, price_snapshot_count,
                               open_order_summary_count, reconciliation_mismatch_count,
                               settled_account_count, settlement_missing_account_count, updated_at
                          from stock_post_close_cycle_metric
                         where close_cycle_id = ?
                        """
                )
                .param(closeCycleId)
                .query((rs, rowNum) -> new EodOperationsOverviewResponse.CycleMetrics(
                        rs.getLong("captured_open_order_count"),
                        rs.getLong("cancelled_order_count"),
                        rs.getBigDecimal("released_buy_cash"),
                        rs.getLong("released_sell_quantity"),
                        rs.getLong("settlement_target_account_count"),
                        rs.getLong("account_snapshot_count"),
                        rs.getLong("holding_snapshot_count"),
                        rs.getLong("price_snapshot_count"),
                        rs.getLong("open_order_summary_count"),
                        rs.getLong("reconciliation_mismatch_count"),
                        rs.getLong("settled_account_count"),
                        rs.getLong("settlement_missing_account_count"),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .optional();
    }

    private Optional<EodOperationsOverviewResponse.PhaseAttempt> findLatestAttempt(long closeCycleId) {
        return jdbcClient.sql(
                        """
                        select id, phase, attempt_no, batch_job_execution_id, owner_id, status,
                               started_at, completed_at, error_code, error_message,
                               build_version, schema_version
                          from stock_post_close_phase_attempt
                         where cycle_id = ?
                         order by id desc
                         limit 1
                        """
                )
                .param(closeCycleId)
                .query((rs, rowNum) -> new EodOperationsOverviewResponse.PhaseAttempt(
                        rs.getLong("id"),
                        rs.getString("phase"),
                        rs.getInt("attempt_no"),
                        nullableLong(rs, "batch_job_execution_id"),
                        rs.getString("owner_id"),
                        rs.getString("status"),
                        rs.getObject("started_at", LocalDateTime.class),
                        rs.getObject("completed_at", LocalDateTime.class),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        rs.getString("build_version"),
                        rs.getString("schema_version")
                ))
                .optional();
    }

    private List<EodOperationsOverviewResponse.ReadinessCheck> findReadinessChecks(long closeCycleId) {
        return jdbcClient.sql(
                        """
                        select check_code, display_order, check_status, failure_count,
                               message, checked_at
                         from stock_post_close_readiness_check
                         where close_cycle_id = ?
                         order by display_order, check_code
                         limit 10
                        """
                )
                .param(closeCycleId)
                .query((rs, rowNum) -> new EodOperationsOverviewResponse.ReadinessCheck(
                        rs.getString("check_code"),
                        rs.getInt("display_order"),
                        rs.getString("check_status"),
                        rs.getLong("failure_count"),
                        rs.getString("message"),
                        rs.getObject("checked_at", LocalDateTime.class)
                ))
                .list();
    }

    private Optional<EodOperationsOverviewResponse.Signal> findLatestSignal(long closeCycleId) {
        return jdbcClient.sql(
                        """
                        select id, signal_type, job_name, execution_mode, status, requested_at,
                               eligible_at, next_attempt_at, attempt_count, max_attempts,
                               processed_count, message, error_message, completed_at
                          from stock_batch_job_signal
                         where expected_cycle_id = ?
                         order by id desc
                         limit 1
                        """
                )
                .param(closeCycleId)
                .query((rs, rowNum) -> new EodOperationsOverviewResponse.Signal(
                        rs.getLong("id"),
                        rs.getString("signal_type"),
                        rs.getString("job_name"),
                        rs.getString("execution_mode"),
                        rs.getString("status"),
                        rs.getObject("requested_at", LocalDateTime.class),
                        rs.getObject("eligible_at", LocalDateTime.class),
                        rs.getObject("next_attempt_at", LocalDateTime.class),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        nullableInteger(rs, "processed_count"),
                        rs.getString("message"),
                        rs.getString("error_message"),
                        rs.getObject("completed_at", LocalDateTime.class)
                ))
                .optional();
    }

    private Long nullableLong(ResultSet rs, String columnName) throws SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }
}
