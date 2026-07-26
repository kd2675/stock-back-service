package stock.back.service.market.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.InstitutionSuspensionRequest;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
public class InstitutionEmergencyStopService {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;
    private final MarketRoleOrderCleanupService marketRoleOrderCleanupService;
    private final TransactionTemplate transactionTemplate;

    public InstitutionEmergencyStopService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SimulationClockService simulationClockService,
            MarketLedgerFreezeGuard marketLedgerFreezeGuard,
            MarketRoleOrderCleanupService marketRoleOrderCleanupService,
            @Qualifier("pubJdbcTransactionManager")
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.simulationClockService = simulationClockService;
        this.marketLedgerFreezeGuard = marketLedgerFreezeGuard;
        this.marketRoleOrderCleanupService = marketRoleOrderCleanupService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void suspend(
            long portfolioId,
            InstitutionSuspensionRequest request,
            String changedBy
    ) {
        if (portfolioId <= 0L) {
            throw StockException.badRequest("Institution portfolio id must be positive");
        }
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        String reason = normalizeReason(request);
        String actor = normalizeChangedBy(changedBy);
        transactionTemplate.executeWithoutResult(status -> {
            LocalDate businessDate = marketLedgerFreezeGuard.acquireJdbcMutationPermit(
                    "institution portfolio emergency suspension"
            );
            // The batch execution path locks intent rows before joining the portfolio/account.
            // Keep the emergency path in the same order to avoid an intent <-> portfolio deadlock.
            rejectPendingIntents(portfolioId, reason, clock.simulationDateTime());
            SuspensionTarget target = suspendPortfolio(
                    portfolioId,
                    businessDate,
                    clock.simulationDateTime(),
                    reason,
                    actor
            );
            marketRoleOrderCleanupService.cancelOpenOrderBookOrders(
                    target.accountId(),
                    "INSTITUTIONAL_INVESTOR",
                    null,
                    clock.simulationDateTime()
            );
        });
    }

    private SuspensionTarget suspendPortfolio(
            long portfolioId,
            LocalDate businessDate,
            LocalDateTime now,
            String reason,
            String changedBy
    ) {
        SuspensionTarget target = jdbcClient.sql(
                        """
                        select id, account_id, portfolio_code,
                               execution_mode, status, policy_version
                          from stock_institution_portfolio
                         where id = ?
                         for update
                        """
                )
                .param(portfolioId)
                .query((rs, rowNum) -> new SuspensionTarget(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("portfolio_code"),
                        rs.getString("execution_mode"),
                        rs.getString("status"),
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElseThrow(() -> StockException.notFound(
                        "Institution portfolio not found: " + portfolioId
                ));
        if (!"LIVE".equals(target.executionMode())) {
            throw StockException.conflict(
                    "Emergency stop is limited to an institution LIVE portfolio"
            );
        }
        if ("SUSPENDED".equals(target.status())) {
            return target;
        }
        if (!"ACTIVE".equals(target.status())) {
            throw StockException.conflict(
                    "Only an active or already suspended institution LIVE portfolio can be stopped"
            );
        }

        long nextPolicyVersion = Math.addExact(target.policyVersion(), 1L);
        int updated = jdbcTemplate.update(
                """
                update stock_institution_portfolio
                   set status = 'SUSPENDED',
                       next_decision_at = null,
                       policy_version = ?,
                       updated_at = ?
                 where id = ?
                   and execution_mode = 'LIVE'
                   and status = 'ACTIVE'
                   and policy_version = ?
                """,
                nextPolicyVersion,
                now,
                target.portfolioId(),
                target.policyVersion()
        );
        requireSingleUpdate(updated, "Institution LIVE emergency stop");
        jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set status = 'RETIRED',
                       updated_at = ?
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = ?
                   and status in ('SCHEDULED', 'ACTIVE')
                """,
                now,
                target.portfolioCode()
        );
        insertEmergencyStopPolicy(
                target,
                nextPolicyVersion,
                businessDate,
                reason,
                changedBy,
                now
        );
        return new SuspensionTarget(
                target.portfolioId(),
                target.accountId(),
                target.portfolioCode(),
                target.executionMode(),
                "SUSPENDED",
                nextPolicyVersion
        );
    }

    private void rejectPendingIntents(
            long portfolioId,
            String reason,
            LocalDateTime rejectedAt
    ) {
        jdbcTemplate.update(
                """
                update stock_institution_order_intent
                   set status = 'REJECTED',
                       submission_reason = ?,
                       updated_at = ?
                 where portfolio_id = ?
                   and status = 'PENDING'
                """,
                truncate("ADMIN_EMERGENCY_SUSPEND:" + reason, 200),
                rejectedAt,
                portfolioId
        );
    }

    private void insertEmergencyStopPolicy(
            SuspensionTarget target,
            long policyVersion,
            LocalDate effectiveBusinessDate,
            String reason,
            String changedBy,
            LocalDateTime now
    ) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("transition", "LIVE_EMERGENCY_SUSPEND");
        config.put("portfolioCode", target.portfolioCode());
        config.put("executionMode", target.executionMode());
        config.put("status", "SUSPENDED");
        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Institution emergency-stop policy JSON serialization failed",
                    ex
            );
        }
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_market_policy_version(
                            policy_scope, scope_key, version_no,
                            effective_business_date, status, config_json,
                            change_reason, changed_by, created_at, updated_at
                        ) values (
                            'INSTITUTIONAL_PORTFOLIO', ?, ?, ?,
                            'ACTIVE', ?, ?, ?, ?, ?
                        )
                        """,
                        target.portfolioCode(),
                        policyVersion,
                        effectiveBusinessDate,
                        configJson,
                        reason,
                        changedBy,
                        now,
                        now
                ),
                "Institution emergency-stop policy version"
        );
    }

    private String normalizeReason(InstitutionSuspensionRequest request) {
        String reason = request == null ? null : request.changeReason();
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) {
            return "Emergency stop of institution LIVE portfolio and cancellation of open orders";
        }
        return truncate(normalized, 500);
    }

    private String normalizeChangedBy(String changedBy) {
        String normalized = changedBy == null ? "" : changedBy.trim();
        if (normalized.isBlank()) {
            return "SYSTEM";
        }
        return truncate(normalized, 64);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(
                    operation + " count mismatch: expected=1, actual=" + count
            );
        }
    }

    private record SuspensionTarget(
            long portfolioId,
            long accountId,
            String portfolioCode,
            String executionMode,
            String status,
            long policyVersion
    ) {
    }
}
