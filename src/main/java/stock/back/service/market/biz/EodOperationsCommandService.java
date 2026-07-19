package stock.back.service.market.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.EodPhaseRetryResponse;

@Service
@RequiredArgsConstructor
@Slf4j
public class EodOperationsCommandService {

    private final JdbcClient jdbcClient;

    /**
     * Releases only the coordinator backoff for the current failed full-market phase. The
     * coordinator still owns phase claim, session checks and job execution. This command never
     * reads or writes order, execution, account or holding ledgers.
     */
    @Transactional
    public EodPhaseRetryResponse retryFailedPhase(long cycleId, String requestedBy) {
        if (cycleId <= 0) {
            throw StockException.badRequest("cycleId must be positive");
        }
        String normalizedRequestedBy = requireRequestedBy(requestedBy);
        CycleRetryTarget target = lockCycle(cycleId);
        validateRetryTarget(target);

        LocalDateTime requestedAt = LocalDateTime.now();
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_cycle
                           set status = 'PENDING',
                               owner_id = null,
                               lease_until = null,
                               next_retry_at = null,
                               version = version + 1,
                               updated_at = ?
                         where id = ?
                           and version = ?
                           and status = 'FAILED'
                           and owner_id is null
                        """
                )
                .param(requestedAt)
                .param(target.id())
                .param(target.version())
                .update();
        if (updated != 1) {
            throw StockException.conflict("EOD cycle changed before the retry request was applied");
        }

        log.info(
                "EOD phase retry queued: cycleId={}, businessDate={}, phase={}, attemptCount={}, requestedBy={}",
                target.id(),
                target.businessDate(),
                target.phase(),
                target.attemptCount(),
                normalizedRequestedBy
        );
        return new EodPhaseRetryResponse(
                target.id(),
                target.businessDate(),
                target.phase(),
                target.status(),
                "PENDING",
                target.attemptCount(),
                normalizedRequestedBy,
                requestedAt
        );
    }

    private CycleRetryTarget lockCycle(long cycleId) {
        return jdbcClient.sql(
                        """
                        select id, business_date, scope_type, scope_key, phase, status,
                               version, attempt_count, owner_id
                          from stock_post_close_cycle
                         where id = ?
                         for update
                        """
                )
                .param(cycleId)
                .query((rs, rowNum) -> new CycleRetryTarget(
                        rs.getLong("id"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getString("scope_type"),
                        rs.getString("scope_key"),
                        rs.getString("phase"),
                        rs.getString("status"),
                        rs.getLong("version"),
                        rs.getInt("attempt_count"),
                        rs.getString("owner_id")
                ))
                .optional()
                .orElseThrow(() -> StockException.notFound("EOD cycle not found: " + cycleId));
    }

    private void validateRetryTarget(CycleRetryTarget target) {
        if (!"FULL_MARKET".equals(target.scopeType()) || !"ALL".equals(target.scopeKey())) {
            throw StockException.conflict("Only a full-market EOD cycle can be retried here");
        }
        if (!"FAILED".equals(target.status())) {
            throw StockException.conflict("Only the current FAILED EOD phase can be retried");
        }
        if (target.ownerId() != null || hasRunningAttempt(target.id())) {
            throw StockException.conflict("The EOD phase still has an active execution owner");
        }
        if (hasOpenMarket()) {
            throw StockException.conflict("A failed EOD phase cannot be retried while the market is open");
        }
        Long oldestIncompleteCycleId = findOldestIncompleteFullMarketCycleId();
        if (oldestIncompleteCycleId == null || oldestIncompleteCycleId != target.id()) {
            throw StockException.conflict("Only the oldest incomplete full-market EOD cycle can be retried");
        }
    }

    private boolean hasRunningAttempt(long cycleId) {
        return jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_post_close_phase_attempt
                             where cycle_id = ?
                               and status = 'RUNNING'
                        )
                        """
                )
                .param(cycleId)
                .query(Boolean.class)
                .single();
    }

    private boolean hasOpenMarket() {
        return jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_order_book_market_config
                             where enabled = true
                               and market_status = 'OPEN'
                        ) or exists(
                            select 1
                              from stock_virtual_market_config
                             where enabled = true
                               and market_status = 'OPEN'
                        ) or exists(
                            select 1
                              from stock_market_session_fence fence
                              join stock_order_book_market_config config
                                on config.symbol = fence.symbol
                               and config.enabled = true
                             where fence.market_type = 'ORDER_BOOK'
                               and fence.session_state = 'OPEN'
                        ) or exists(
                            select 1
                              from stock_market_session_fence fence
                              join stock_virtual_market_config config
                                on config.symbol = fence.symbol
                               and config.enabled = true
                             where fence.market_type = 'VIRTUAL_PRICE'
                               and fence.session_state = 'OPEN'
                        )
                        """
                )
                .query(Boolean.class)
                .single();
    }

    private Long findOldestIncompleteFullMarketCycleId() {
        return jdbcClient.sql(
                        """
                        select id
                          from stock_post_close_cycle
                         where scope_type = 'FULL_MARKET'
                           and scope_key = 'ALL'
                           and status <> 'COMPLETED'
                         order by business_date, id
                         limit 1
                        """
                )
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private String requireRequestedBy(String requestedBy) {
        if (requestedBy == null || requestedBy.isBlank()) {
            throw StockException.badRequest("requestedBy is required");
        }
        return requestedBy.trim();
    }

    private record CycleRetryTarget(
            long id,
            LocalDate businessDate,
            String scopeType,
            String scopeKey,
            String phase,
            String status,
            long version,
            int attemptCount,
            String ownerId
    ) {
    }
}
