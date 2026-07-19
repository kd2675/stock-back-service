package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.StockBatchJobRunResponse;
import web.common.core.simulation.SimulationMarketSession;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class BatchJobSignalService {

    private static final String SIGNAL_AUTO_PARTICIPANT_CASH_FLOW_RUN = "AUTO_PARTICIPANT_CASH_FLOW_RUN";
    private static final String SIGNAL_MARKET_CLOSE_ROLLOVER_RUN = "MARKET_CLOSE_ROLLOVER_RUN";
    private static final String SIGNAL_MARKET_CLOSE_ROLLOVER_SYMBOL = "MARKET_CLOSE_ROLLOVER_SYMBOL";
    private static final String SIGNAL_ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL = "ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL";
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_SKIPPED = "SKIPPED";
    private static final String MANUAL_CASH_FLOW_AUTO_ENABLED_MESSAGE =
            "Manual recurring cash is allowed only when automatic cash flow is disabled";

    private final JdbcTemplate jdbcTemplate;
    private final BatchJobRuntimeControlService batchJobRuntimeControlService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final BatchJobSignalContextService batchJobSignalContextService;
    private final int defaultMaxAttempts;

    public BatchJobSignalService(
            JdbcTemplate jdbcTemplate,
            BatchJobRuntimeControlService batchJobRuntimeControlService,
            SimulationMarketSessionService simulationMarketSessionService,
            BatchJobSignalContextService batchJobSignalContextService,
            @Value("${stock.batch-signal.default-max-attempts:12}") int defaultMaxAttempts
    ) {
        if (defaultMaxAttempts <= 0) {
            throw new IllegalArgumentException("stock.batch-signal.default-max-attempts must be positive");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.batchJobRuntimeControlService = batchJobRuntimeControlService;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.batchJobSignalContextService = batchJobSignalContextService;
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public StockBatchJobRunResponse enqueueAutoParticipantCashFlow(String requestedBy) {
        if (batchJobRuntimeControlService.cashFlowStatus().effectiveEnabled()) {
            return skippedResponse(
                    BatchJobNames.AUTO_PARTICIPANT_CASH_FLOW,
                    "manual-recurring-cash",
                    MANUAL_CASH_FLOW_AUTO_ENABLED_MESSAGE,
                    LocalDateTime.now()
            );
        }
        return enqueueDeduplicated(
                SIGNAL_AUTO_PARTICIPANT_CASH_FLOW_RUN,
                BatchJobNames.AUTO_PARTICIPANT_CASH_FLOW,
                "manual-recurring-cash",
                null,
                requestedBy,
                batchJobSignalContextService.resolveFullMarket(),
                true
        );
    }

    @Transactional(readOnly = true)
    public Optional<StockBatchJobRunResponse> latestAutoParticipantCashFlow() {
        return jdbcTemplate.query(
                        """
                        select job_name,
                               case when status in ('PENDING', 'DEFERRED') then 'QUEUED' else status end as response_status,
                               execution_mode,
                               coalesce(processed_count, 0) as processed_count,
                               coalesce(message, case
                                   when status = 'PENDING' then 'Batch job signal queued'
                                   when status = 'DEFERRED' then 'Batch job signal deferred until its eligible time'
                                   when status = 'PROCESSING' then 'Batch job signal processing'
                                   else 'Batch job signal completed'
                               end) as response_message,
                               requested_at,
                               completed_at
                          from stock_batch_job_signal
                         where signal_type = ?
                           and job_name = ?
                           and execution_mode = 'manual-recurring-cash'
                         order by id desc
                         limit 1
                        """,
                        (rs, rowNum) -> new StockBatchJobRunResponse(
                                rs.getString("job_name"),
                                rs.getString("response_status"),
                                rs.getString("execution_mode"),
                                rs.getInt("processed_count"),
                                rs.getString("response_message"),
                                rs.getObject("requested_at", LocalDateTime.class),
                                rs.getObject("completed_at", LocalDateTime.class)
                        ),
                        SIGNAL_AUTO_PARTICIPANT_CASH_FLOW_RUN,
                        BatchJobNames.AUTO_PARTICIPANT_CASH_FLOW
                )
                .stream()
                .findFirst();
    }

    @Transactional
    public StockBatchJobRunResponse enqueueMarketCloseRollover(String requestedBy) {
        if (simulationMarketSessionService.currentSession() != SimulationMarketSession.AFTER_CLOSE) {
            throw StockException.conflict("Full market close can only be requested after the regular session");
        }
        return enqueue(
                SIGNAL_MARKET_CLOSE_ROLLOVER_RUN,
                BatchJobNames.MARKET_CLOSE_ROLLOVER,
                "manual-rollover",
                null,
                requestedBy,
                batchJobSignalContextService.resolveFullMarket(),
                false
        );
    }

    @Transactional
    public StockBatchJobRunResponse enqueueMarketCloseRollover(String symbol, String requestedBy) {
        String normalizedSymbol = normalizeSymbol(symbol);
        return enqueue(
                SIGNAL_MARKET_CLOSE_ROLLOVER_SYMBOL,
                BatchJobNames.MARKET_CLOSE_ROLLOVER,
                "price-limit-base:" + normalizedSymbol,
                normalizedSymbol,
                requestedBy,
                batchJobSignalContextService.resolveSymbol(normalizedSymbol),
                false
        );
    }

    @Transactional
    public StockBatchJobRunResponse enqueueOpenOrderBookOrderCancel(String symbol, String requestedBy) {
        String normalizedSymbol = normalizeSymbol(symbol);
        return enqueue(
                SIGNAL_ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL,
                BatchJobNames.MARKET_CLOSE_ROLLOVER,
                "halt-open-order-cancel:" + normalizedSymbol,
                normalizedSymbol,
                requestedBy,
                batchJobSignalContextService.resolveSymbol(normalizedSymbol),
                false
        );
    }

    private StockBatchJobRunResponse enqueue(
            String signalType,
            String jobName,
            String executionMode,
            String symbol,
            String requestedBy,
            BatchJobSignalContextService.BatchJobSignalContext context,
            boolean overnightEligible
    ) {
        LocalDateTime now = LocalDateTime.now();
        return insertSignal(
                signalType,
                jobName,
                executionMode,
                symbol,
                requestedBy,
                context,
                overnightEligible,
                now
        );
    }

    private StockBatchJobRunResponse enqueueDeduplicated(
            String signalType,
            String jobName,
            String executionMode,
            String symbol,
            String requestedBy,
            BatchJobSignalContextService.BatchJobSignalContext context,
            boolean overnightEligible
    ) {
        LocalDateTime now = LocalDateTime.now();
        Optional<Long> existingSignalId = findOpenSignalId(signalType, jobName, executionMode, symbol);
        if (existingSignalId.isPresent()) {
            return queuedResponse(
                    jobName,
                    executionMode,
                    "Batch job signal already queued: id=" + existingSignalId.get(),
                    now
            );
        }
        return insertSignal(
                signalType,
                jobName,
                executionMode,
                symbol,
                requestedBy,
                context,
                overnightEligible,
                now
        );
    }

    private Optional<Long> findOpenSignalId(
            String signalType,
            String jobName,
            String executionMode,
            String symbol
    ) {
        List<Long> signalIds = jdbcTemplate.query(
                """
                select id
                  from stock_batch_job_signal
                 where signal_type = ?
                   and job_name = ?
                   and execution_mode = ?
                   and ((? is null and symbol is null) or symbol = ?)
                   and status in ('PENDING', 'DEFERRED', 'PROCESSING')
                 order by id asc
                 limit 1
                """,
                (rs, rowNum) -> rs.getLong("id"),
                signalType,
                jobName,
                executionMode,
                symbol,
                symbol
        );
        return signalIds.stream().findFirst();
    }

    private StockBatchJobRunResponse insertSignal(
            String signalType,
            String jobName,
            String executionMode,
            String symbol,
            String requestedBy,
            BatchJobSignalContextService.BatchJobSignalContext context,
            boolean overnightEligible,
            LocalDateTime now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    insert into stock_batch_job_signal(
                        signal_type,
                        job_name,
                        execution_mode,
                        symbol,
                        payload_json,
                        status,
                        requested_by,
                        requested_at,
                        requested_business_date,
                        requested_session_epoch,
                        expected_cycle_id,
                        eligible_at,
                        next_attempt_at,
                        attempt_count,
                        max_attempts,
                        created_at,
                        updated_at
                    )
                    values (?, ?, ?, ?, null, 'PENDING', ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, signalType);
            statement.setString(2, jobName);
            statement.setString(3, executionMode);
            statement.setString(4, symbol);
            statement.setString(5, normalizeRequestedBy(requestedBy));
            statement.setObject(6, now);
            statement.setObject(7, context.requestedBusinessDate());
            statement.setObject(8, context.requestedSessionEpoch());
            statement.setObject(9, context.expectedCycleId());
            statement.setObject(10, eligibleAt(context, overnightEligible));
            statement.setObject(11, now);
            statement.setInt(12, defaultMaxAttempts);
            statement.setObject(13, now);
            statement.setObject(14, now);
            return statement;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        long signalId = generatedId == null ? 0L : generatedId.longValue();
        return queuedResponse(
                jobName,
                executionMode,
                "Batch job signal queued: id=" + signalId,
                now
        );
    }

    private LocalDateTime eligibleAt(
            BatchJobSignalContextService.BatchJobSignalContext context,
            boolean overnightEligible
    ) {
        if (!overnightEligible) {
            return context.simulationNow();
        }
        LocalDateTime overnightStart = context.requestedBusinessDate().plusDays(1).atStartOfDay();
        return context.simulationNow().isAfter(overnightStart) ? context.simulationNow() : overnightStart;
    }

    private StockBatchJobRunResponse queuedResponse(
            String jobName,
            String executionMode,
            String message,
            LocalDateTime now
    ) {
        return new StockBatchJobRunResponse(
                jobName,
                STATUS_QUEUED,
                executionMode,
                0,
                message,
                now,
                null
        );
    }

    private StockBatchJobRunResponse skippedResponse(
            String jobName,
            String executionMode,
            String message,
            LocalDateTime now
    ) {
        return new StockBatchJobRunResponse(
                jobName,
                STATUS_SKIPPED,
                executionMode,
                0,
                message,
                now,
                now
        );
    }

    private String normalizeSymbol(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            throw new IllegalArgumentException("symbol is required");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRequestedBy(String requestedBy) {
        if (!StringUtils.hasText(requestedBy)) {
            return "SYSTEM";
        }
        String normalized = requestedBy.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }
}
