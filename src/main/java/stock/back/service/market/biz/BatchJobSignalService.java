package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import stock.back.service.market.vo.StockBatchJobRunResponse;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class BatchJobSignalService {

    private static final String SIGNAL_AUTO_PARTICIPANT_CASH_FLOW_RUN = "AUTO_PARTICIPANT_CASH_FLOW_RUN";
    private static final String SIGNAL_MARKET_CLOSE_ROLLOVER_RUN = "MARKET_CLOSE_ROLLOVER_RUN";
    private static final String SIGNAL_MARKET_CLOSE_ROLLOVER_SYMBOL = "MARKET_CLOSE_ROLLOVER_SYMBOL";
    private static final String SIGNAL_ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL = "ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL";
    private static final String STATUS_QUEUED = "QUEUED";

    private final JdbcTemplate jdbcTemplate;

    public BatchJobSignalService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public StockBatchJobRunResponse enqueueAutoParticipantCashFlow(String requestedBy) {
        return enqueue(
                SIGNAL_AUTO_PARTICIPANT_CASH_FLOW_RUN,
                BatchJobNames.AUTO_PARTICIPANT_CASH_FLOW,
                "manual-recurring-cash",
                null,
                requestedBy
        );
    }

    @Transactional
    public StockBatchJobRunResponse enqueueMarketCloseRollover(String requestedBy) {
        return enqueue(
                SIGNAL_MARKET_CLOSE_ROLLOVER_RUN,
                BatchJobNames.MARKET_CLOSE_ROLLOVER,
                "manual-rollover",
                null,
                requestedBy
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
                requestedBy
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
                requestedBy
        );
    }

    private StockBatchJobRunResponse enqueue(
            String signalType,
            String jobName,
            String executionMode,
            String symbol,
            String requestedBy
    ) {
        LocalDateTime now = LocalDateTime.now();
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
                        created_at,
                        updated_at
                    )
                    values (?, ?, ?, ?, null, 'PENDING', ?, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, signalType);
            statement.setString(2, jobName);
            statement.setString(3, executionMode);
            statement.setString(4, symbol);
            statement.setString(5, normalizeRequestedBy(requestedBy));
            statement.setObject(6, now);
            statement.setObject(7, now);
            statement.setObject(8, now);
            return statement;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        long signalId = generatedId == null ? 0L : generatedId.longValue();
        return new StockBatchJobRunResponse(
                jobName,
                STATUS_QUEUED,
                executionMode,
                0,
                "Batch job signal queued: id=" + signalId,
                now,
                null
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
