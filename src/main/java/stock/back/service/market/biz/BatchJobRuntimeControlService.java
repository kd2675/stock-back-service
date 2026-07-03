package stock.back.service.market.biz;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.AutoParticipantCashFlowStatusResponse;
import stock.back.service.market.vo.BatchJobRuntimeStatusResponse;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BatchJobRuntimeControlService {

    private static final String SYSTEM_UPDATED_BY = "SYSTEM";
    private static final boolean DEFAULT_CONTROL_ROW_ENABLED = true;

    private final JdbcClient jdbcClient;
    private final Map<String, RuntimeDefinition> definitions;

    public BatchJobRuntimeControlService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
        this.definitions = createDefinitions();
    }

    @Transactional
    public AutoParticipantCashFlowStatusResponse cashFlowStatus() {
        BatchJobRuntimeStatusResponse status = status(BatchJobNames.AUTO_PARTICIPANT_CASH_FLOW);
        return new AutoParticipantCashFlowStatusResponse(
                status.schedulerConfigured(),
                status.runtimeEnabled(),
                status.effectiveEnabled(),
                status.updatedBy(),
                status.updatedAt()
        );
    }

    @Transactional
    public AutoParticipantCashFlowStatusResponse updateCashFlowStatus(boolean runtimeEnabled, String updatedBy) {
        BatchJobRuntimeStatusResponse status = update(BatchJobNames.AUTO_PARTICIPANT_CASH_FLOW, runtimeEnabled, updatedBy);
        return new AutoParticipantCashFlowStatusResponse(
                status.schedulerConfigured(),
                status.runtimeEnabled(),
                status.effectiveEnabled(),
                status.updatedBy(),
                status.updatedAt()
        );
    }

    @Transactional
    public List<BatchJobRuntimeStatusResponse> statuses() {
        return definitions.values().stream()
                .map(definition -> toResponse(findOrCreateControlRow(definition.jobName())))
                .toList();
    }

    @Transactional
    public BatchJobRuntimeStatusResponse update(String jobName, boolean runtimeEnabled, String updatedBy) {
        RuntimeDefinition definition = requireDefinition(jobName);
        findOrCreateControlRow(definition.jobName());
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql(
                        """
                        update stock_batch_job_control
                           set runtime_enabled = ?,
                               updated_by = ?,
                               updated_at = ?
                         where job_name = ?
                        """
                )
                .param(runtimeEnabled)
                .param(normalizeUpdatedBy(updatedBy))
                .param(now)
                .param(definition.jobName())
                .update();
        return toResponse(requireControlRow(definition.jobName()));
    }

    private BatchJobRuntimeStatusResponse status(String jobName) {
        RuntimeDefinition definition = requireDefinition(jobName);
        return toResponse(findOrCreateControlRow(definition.jobName()));
    }

    private RuntimeDefinition requireDefinition(String jobName) {
        String normalizedJobName;
        try {
            normalizedJobName = BatchJobNames.normalize(jobName);
        } catch (IllegalArgumentException ex) {
            throw StockException.badRequest(ex.getMessage());
        }
        RuntimeDefinition definition = definitions.get(normalizedJobName);
        if (definition == null) {
            throw StockException.notFound("Unknown batch job: " + normalizedJobName);
        }
        return definition;
    }

    private ControlRow findOrCreateControlRow(String jobName) {
        return findControlRow(jobName)
                .orElseGet(() -> {
                    insertInitialControlRow(jobName);
                    return requireControlRow(jobName);
                });
    }

    private ControlRow requireControlRow(String jobName) {
        return findControlRow(jobName)
                .orElseThrow(() -> new IllegalStateException("Batch job runtime control row not found: " + jobName));
    }

    private Optional<ControlRow> findControlRow(String jobName) {
        return jdbcClient.sql(
                        """
                        select job_name, runtime_enabled, scheduler_configured, updated_by, updated_at
                          from stock_batch_job_control
                         where job_name = ?
                        """
                )
                .param(jobName)
                .query((rs, rowNum) -> new ControlRow(
                        rs.getString("job_name"),
                        rs.getBoolean("runtime_enabled"),
                        rs.getBoolean("scheduler_configured"),
                        rs.getString("updated_by"),
                        toLocalDateTime(rs.getTimestamp("updated_at"))
                ))
                .optional();
    }

    private void insertInitialControlRow(String jobName) {
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcClient.sql(
                            """
                            insert into stock_batch_job_control(
                                job_name,
                                runtime_enabled,
                                scheduler_configured,
                                updated_by,
                                created_at,
                                updated_at
                            )
                            values (?, ?, ?, ?, ?, ?)
                            """
                    )
                    .param(jobName)
                    .param(DEFAULT_CONTROL_ROW_ENABLED)
                    .param(true)
                    .param(SYSTEM_UPDATED_BY)
                    .param(now)
                    .param(now)
                    .update();
        } catch (DuplicateKeyException ignored) {
            // Another backend or batch instance initialized the shared row first.
        }
    }

    private BatchJobRuntimeStatusResponse toResponse(ControlRow controlRow) {
        return new BatchJobRuntimeStatusResponse(
                controlRow.jobName(),
                controlRow.schedulerConfigured(),
                controlRow.runtimeEnabled(),
                controlRow.schedulerConfigured() && controlRow.runtimeEnabled(),
                controlRow.updatedBy(),
                controlRow.updatedAt()
        );
    }

    private Map<String, RuntimeDefinition> createDefinitions() {
        Map<String, RuntimeDefinition> createdDefinitions = new LinkedHashMap<>();
        put(createdDefinitions, BatchJobNames.MARKET_DATA_REFRESH);
        put(createdDefinitions, BatchJobNames.VIRTUAL_PRICE_EXECUTION);
        put(createdDefinitions, BatchJobNames.ORDER_BOOK_EXECUTION);
        put(createdDefinitions, BatchJobNames.CORPORATE_ACTIONS);
        put(createdDefinitions, BatchJobNames.AUTO_MARKET);
        put(createdDefinitions, BatchJobNames.AUTO_MARKET_ORDER_EXPIRY);
        put(createdDefinitions, BatchJobNames.LISTING_AUTO_MARKET);
        put(createdDefinitions, BatchJobNames.AUTO_PARTICIPANT_CASH_FLOW);
        put(createdDefinitions, BatchJobNames.MARKET_CLOSE_ROLLOVER);
        put(createdDefinitions, BatchJobNames.PORTFOLIO_SETTLEMENT);
        put(createdDefinitions, BatchJobNames.HOLDING_CLEANUP);
        return Collections.unmodifiableMap(createdDefinitions);
    }

    private void put(Map<String, RuntimeDefinition> createdDefinitions, String jobName) {
        createdDefinitions.put(jobName, new RuntimeDefinition(jobName));
    }

    private String normalizeUpdatedBy(String updatedBy) {
        if (!StringUtils.hasText(updatedBy)) {
            return SYSTEM_UPDATED_BY;
        }
        String normalized = updatedBy.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record RuntimeDefinition(
            String jobName
    ) {
    }

    private record ControlRow(
            String jobName,
            boolean runtimeEnabled,
            boolean schedulerConfigured,
            String updatedBy,
            LocalDateTime updatedAt
    ) {
    }
}
