package stock.back.service.market.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.UnderwritingSupplyActivationRequest;
import stock.back.service.market.vo.UnderwritingSupplySuspensionRequest;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

@Service
public class UnderwritingSupplyTransitionService {

    static final BigDecimal DEFAULT_SUPPLY_RATE = new BigDecimal("0.100000");
    static final BigDecimal MIN_SUPPLY_RATE = new BigDecimal("0.010000");
    static final BigDecimal MAX_SUPPLY_RATE = new BigDecimal("0.250000");
    static final int DEFAULT_DURATION_DAYS = 20;
    static final int MIN_DURATION_DAYS = 1;
    static final int MAX_DURATION_DAYS = 60;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService marketSessionService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;
    private final MarketRoleOrderCleanupService marketRoleOrderCleanupService;

    public UnderwritingSupplyTransitionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SimulationClockService simulationClockService,
            SimulationMarketSessionService marketSessionService,
            MarketLedgerFreezeGuard marketLedgerFreezeGuard,
            MarketRoleOrderCleanupService marketRoleOrderCleanupService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.simulationClockService = simulationClockService;
        this.marketSessionService = marketSessionService;
        this.marketLedgerFreezeGuard = marketLedgerFreezeGuard;
        this.marketRoleOrderCleanupService = marketRoleOrderCleanupService;
    }

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public void activate(
            long contractId,
            UnderwritingSupplyActivationRequest request,
            String changedBy
    ) {
        requirePositiveContractId(contractId);
        requirePausedPreOpen();
        LocalDate businessDate = marketLedgerFreezeGuard.acquireJdbcPreOpenMutationPermit(
                "issue-underwriter scaled supply activation"
        );
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        if (!clock.simulationDate().equals(businessDate)) {
            throw StockException.conflict(
                    "Simulation date changed while activating issue-underwriter supply"
            );
        }

        ContractTarget target = lockContract(contractId);
        if ("STABILIZING".equals(target.status())) {
            return;
        }
        if (!"ALLOCATED".equals(target.status())) {
            throw StockException.conflict(
                    "Issue-underwriter supply cannot activate from status " + target.status()
            );
        }
        requireSupplyMarketPrerequisites(target.symbol());
        RoleSnapshot role = lockRoleSnapshot(target, businessDate);
        validateDedicatedRole(role);
        validateSupplyReconciliation(target);
        if (role.openContractOrderCount() > 0L) {
            throw StockException.conflict(
                    "Issue-underwriter contract already has open orders; suspend and reconcile first"
            );
        }

        BigDecimal supplyRate = normalizedSupplyRate(request);
        int durationDays = normalizedDurationDays(request);
        long availableQuantity = Math.max(
                0L,
                role.holdingQuantity() - role.reservedQuantity()
        );
        /*
         * Initial-allocation quantities stay immutable audit facts after a stock split,
         * while holdings and active supply limits move to the new share unit. Use the
         * current unreserved inventory as the policy base and keep the contract limit
         * as a separate lifetime cap.
         */
        long requestedSupplyQuantity = BigDecimal.valueOf(availableQuantity)
                .multiply(supplyRate)
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
        requestedSupplyQuantity = Math.min(
                Math.max(1L, requestedSupplyQuantity),
                availableQuantity
        );
        if (requestedSupplyQuantity <= 0L) {
            throw StockException.conflict(
                    "No unreserved issue-underwriter inventory remains"
            );
        }
        SupplyUsage usage = findSupplyUsage(contractId);
        /*
         * Daily states retain submitted budget across suspend/resume. Contract limits are
         * cumulative, so a newly approved tranche must be added to past usage instead of
         * comparing a percentage of remaining inventory directly with historical usage.
         */
        long quantityLimit = Math.addExact(
                usage.submittedQuantity(),
                requestedSupplyQuantity
        );
        BigDecimal amountLimit = usage.submittedAmount().add(
                target.issuePrice().multiply(BigDecimal.valueOf(requestedSupplyQuantity))
        )
                .setScale(2, RoundingMode.HALF_UP);

        LocalDate endDate = businessDate.plusDays(durationDays - 1L);
        long nextPolicyVersion = Math.addExact(target.policyVersion(), 1L);
        LocalDateTime now = clock.simulationDateTime();
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_underwriting_contract
                           set stabilization_start_date = ?,
                               stabilization_end_date = ?,
                               stabilization_quantity_limit = ?,
                               stabilization_amount_limit = ?,
                               status = 'STABILIZING',
                               policy_version = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'ALLOCATED'
                        """,
                        businessDate,
                        endDate,
                        quantityLimit,
                        amountLimit,
                        nextPolicyVersion,
                        now,
                        contractId
                ),
                "Issue-underwriter supply activation"
        );
        insertPolicyVersion(
                target,
                nextPolicyVersion,
                businessDate,
                "STABILIZING",
                supplyRate,
                durationDays,
                quantityLimit,
                amountLimit,
                normalizeReason(
                        request == null ? null : request.changeReason(),
                        "Activate finite passive issue-underwriter supply"
                ),
                normalizeChangedBy(changedBy),
                now
        );
    }

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public void suspend(
            long contractId,
            UnderwritingSupplySuspensionRequest request,
            String changedBy
    ) {
        requirePositiveContractId(contractId);
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDate businessDate = marketLedgerFreezeGuard.acquireJdbcMutationPermit(
                "issue-underwriter supply emergency suspension"
        );
        ContractTarget target = lockContract(contractId);
        if ("ALLOCATED".equals(target.status())) {
            return;
        }
        if (!"STABILIZING".equals(target.status())) {
            throw StockException.conflict(
                    "Only an active issue-underwriter supply contract can be suspended"
            );
        }
        RoleSnapshot role = lockRoleSnapshot(target, businessDate);
        validateDedicatedRole(role);

        LocalDateTime now = clock.simulationDateTime();
        long nextPolicyVersion = Math.addExact(target.policyVersion(), 1L);
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_underwriting_contract
                           set status = 'ALLOCATED',
                               policy_version = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'STABILIZING'
                        """,
                        nextPolicyVersion,
                        now,
                        contractId
                ),
                "Issue-underwriter supply suspension"
        );
        int cancelledOrderCount = marketRoleOrderCleanupService.cancelOpenOrderBookOrders(
                target.accountId(),
                "ISSUE_UNDERWRITER",
                target.symbol(),
                now
        );
        markDailyStateSuspended(
                contractId,
                businessDate,
                cancelledOrderCount,
                nextPolicyVersion,
                now
        );
        insertPolicyVersion(
                target,
                nextPolicyVersion,
                businessDate,
                "ALLOCATED",
                null,
                null,
                target.stabilizationQuantityLimit(),
                target.stabilizationAmountLimit(),
                normalizeReason(
                        request == null ? null : request.changeReason(),
                        "Suspend issue-underwriter supply and cancel open orders"
                ),
                normalizeChangedBy(changedBy),
                now
        );
    }

    private void requirePausedPreOpen() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        if (clock.running()) {
            throw StockException.conflict(
                    "Pause the simulation clock before activating issue-underwriter supply"
            );
        }
        if (marketSessionService.currentSession() != SimulationMarketSession.PRE_OPEN) {
            throw StockException.conflict(
                    "Issue-underwriter supply can only activate during a paused pre-open"
            );
        }
    }

    private ContractTarget lockContract(long contractId) {
        return jdbcClient.sql(
                        """
                        select id, contract_code, symbol, participant_id, account_id,
                               total_issue_quantity, tradable_allocation_quantity,
                               locked_allocation_quantity, external_allocation_quantity,
                               underwritten_quantity, issue_price,
                               stabilization_quantity_limit,
                               stabilization_amount_limit,
                               status, policy_version
                          from stock_underwriting_contract
                         where id = :contractId
                         for update
                        """
                )
                .param("contractId", contractId)
                .query((rs, rowNum) -> new ContractTarget(
                        rs.getLong("id"),
                        rs.getString("contract_code"),
                        rs.getString("symbol"),
                        rs.getLong("participant_id"),
                        rs.getLong("account_id"),
                        rs.getLong("total_issue_quantity"),
                        rs.getLong("tradable_allocation_quantity"),
                        rs.getLong("locked_allocation_quantity"),
                        rs.getLong("external_allocation_quantity"),
                        rs.getLong("underwritten_quantity"),
                        rs.getBigDecimal("issue_price"),
                        rs.getLong("stabilization_quantity_limit"),
                        rs.getBigDecimal("stabilization_amount_limit"),
                        rs.getString("status"),
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElseThrow(() -> StockException.notFound(
                        "Unknown underwriting contract: " + contractId
                ));
    }

    private RoleSnapshot lockRoleSnapshot(
            ContractTarget target,
            LocalDate businessDate
    ) {
        return jdbcClient.sql(
                        """
                        select account.status as account_status,
                               account.participant_category,
                               account.self_trade_group_id as account_self_trade_group_id,
                               participant.participant_type,
                               participant.status as participant_status,
                               participant.self_trade_group_id as participant_self_trade_group_id,
                               role_mapping.account_role,
                               role_mapping.status as role_mapping_status,
                               role_mapping.effective_from,
                               role_mapping.effective_to,
                               coalesce(holding.quantity, 0) as holding_quantity,
                               coalesce(holding.reserved_quantity, 0) as reserved_quantity,
                               (
                                   select count(*)
                                     from stock_holding other_holding
                                    where other_holding.account_id = :accountId
                                      and other_holding.symbol <> :symbol
                                      and (
                                          other_holding.quantity > 0
                                          or other_holding.reserved_quantity > 0
                                      )
                               ) as unmanaged_holding_count,
                               (
                                   select count(*)
                                     from stock_order open_order
                                    where open_order.account_id = :accountId
                                      and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                                      and open_order.quantity > open_order.filled_quantity
                                      and not exists (
                                          select 1
                                            from stock_order_strategy_origin strategy_origin
                                           where strategy_origin.order_id = open_order.id
                                             and strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                                             and strategy_origin.underwriting_contract_id = :contractId
                                      )
                               ) as non_contract_open_order_count,
                               (
                                   select count(*)
                                     from stock_order_strategy_origin strategy_origin
                                     join stock_order open_order
                                       on open_order.id = strategy_origin.order_id
                                    where strategy_origin.underwriting_contract_id = :contractId
                                      and strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                                      and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                                      and open_order.quantity > open_order.filled_quantity
                               ) as open_contract_order_count
                          from stock_account account
                          join stock_market_participant participant
                            on participant.id = :participantId
                          left join stock_market_participant_account role_mapping
                            on role_mapping.participant_id = participant.id
                           and role_mapping.account_id = account.id
                          left join stock_holding holding
                            on holding.account_id = account.id
                           and holding.symbol = :symbol
                         where account.id = :accountId
                         for update
                        """
                )
                .param("contractId", target.contractId())
                .param("participantId", target.participantId())
                .param("accountId", target.accountId())
                .param("symbol", target.symbol())
                .query((rs, rowNum) -> new RoleSnapshot(
                        rs.getString("account_status"),
                        rs.getString("participant_category"),
                        rs.getString("account_self_trade_group_id"),
                        rs.getString("participant_type"),
                        rs.getString("participant_status"),
                        rs.getString("participant_self_trade_group_id"),
                        rs.getString("account_role"),
                        rs.getString("role_mapping_status"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getLong("holding_quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getLong("unmanaged_holding_count"),
                        rs.getLong("non_contract_open_order_count"),
                        rs.getLong("open_contract_order_count"),
                        businessDate
                ))
                .optional()
                .orElseThrow(() -> StockException.conflict(
                        "Issue-underwriter role mapping is missing"
                ));
    }

    private void validateDedicatedRole(RoleSnapshot role) {
        boolean roleDatesActive = role.effectiveFrom() != null
                && !role.businessDate().isBefore(role.effectiveFrom())
                && (role.effectiveTo() == null
                || !role.businessDate().isAfter(role.effectiveTo()));
        boolean selfTradeGroupMatched = role.participantSelfTradeGroupId() != null
                && role.participantSelfTradeGroupId().equals(role.accountSelfTradeGroupId());
        if (!"ACTIVE".equals(role.accountStatus())
                || !"ACTIVE".equals(role.participantStatus())
                || !"ACTIVE".equals(role.roleMappingStatus())
                || !"ISSUE_UNDERWRITER".equals(role.participantCategory())
                || !"ISSUE_UNDERWRITER".equals(role.participantType())
                || !"ISSUE_UNDERWRITER".equals(role.accountRole())
                || !roleDatesActive
                || !selfTradeGroupMatched) {
            throw StockException.conflict(
                    "Issue-underwriter participant, account, mapping, or self-trade group is invalid"
            );
        }
        if (role.holdingQuantity() < 0L
                || role.reservedQuantity() < 0L
                || role.reservedQuantity() > role.holdingQuantity()) {
            throw StockException.conflict("Issue-underwriter holding reservation is invalid");
        }
        if (role.unmanagedHoldingCount() > 0L) {
            throw StockException.conflict(
                    "Issue-underwriter account contains holdings for another symbol"
            );
        }
        if (role.nonContractOpenOrderCount() > 0L) {
            throw StockException.conflict(
                    "Issue-underwriter account contains an order not owned by this contract"
            );
        }
    }

    private void validateSupplyReconciliation(ContractTarget target) {
        SupplyReconciliation reconciliation = jdbcClient.sql(
                        """
                        select instrument.issued_shares,
                               instrument.tradable_shares,
                               (
                                   select coalesce(sum(holding.quantity), 0)
                                     from stock_holding holding
                                    where holding.symbol = :symbol
                               ) as total_holding_quantity,
                               (
                                   select count(*)
                                     from stock_holding holding
                                    where holding.symbol = :symbol
                                      and (
                                          holding.quantity < 0
                                          or holding.reserved_quantity < 0
                                          or holding.reserved_quantity > holding.quantity
                                      )
                               ) as invalid_holding_count,
                               (
                                   select coalesce(sum(allocation.quantity), 0)
                                     from stock_security_allocation_ledger allocation
                                    where allocation.underwriting_contract_id = :contractId
                                      and allocation.event_type = 'INITIAL_ISSUE'
                                      and allocation.source_account_id is null
                               ) as initial_ledger_quantity,
                               (
                                   select coalesce(sum(allocation.quantity), 0)
                                     from stock_security_allocation_ledger allocation
                                    where allocation.underwriting_contract_id = :contractId
                                      and allocation.event_type = 'INITIAL_ISSUE'
                                      and allocation.source_account_id is null
                                      and allocation.tradability_status = 'TRADABLE'
                               ) as initial_tradable_ledger_quantity,
                               (
                                   select coalesce(sum(allocation.quantity), 0)
                                     from stock_security_allocation_ledger allocation
                                    where allocation.underwriting_contract_id = :contractId
                                      and allocation.event_type = 'INITIAL_ISSUE'
                                      and allocation.source_account_id is null
                                      and allocation.tradability_status = 'LOCKED'
                               ) as initial_locked_ledger_quantity
                          from stock_order_book_instrument instrument
                         where instrument.symbol = :symbol
                        """
                )
                .param("symbol", target.symbol())
                .param("contractId", target.contractId())
                .query((rs, rowNum) -> new SupplyReconciliation(
                        rs.getLong("issued_shares"),
                        rs.getLong("tradable_shares"),
                        rs.getLong("total_holding_quantity"),
                        rs.getLong("invalid_holding_count"),
                        rs.getLong("initial_ledger_quantity"),
                        rs.getLong("initial_tradable_ledger_quantity"),
                        rs.getLong("initial_locked_ledger_quantity")
                ))
                .optional()
                .orElseThrow(() -> StockException.conflict(
                        "Issue-underwriter instrument reconciliation is missing"
                ));
        if (!reconciliation.matches(target)) {
            throw StockException.conflict(
                    "Issue-underwriter supply reconciliation failed; "
                            + "repair contract, allocation ledger, and issued-share holdings first"
            );
        }
    }

    private void requireSupplyMarketPrerequisites(String symbol) {
        Integer ready = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market_config
                            on market_config.symbol = instrument.symbol
                          join stock_auto_market_config auto_config
                            on auto_config.symbol = instrument.symbol
                          join stock_price price
                            on price.symbol = instrument.symbol
                         where instrument.symbol = :symbol
                           and instrument.enabled = true
                           and instrument.tradable_shares > 0
                           and instrument.tick_size > 0
                           and market_config.enabled = true
                           and auto_config.enabled = true
                           and price.current_price > 0
                           and price.previous_close > 0
                        """
                )
                .param("symbol", symbol)
                .query(Integer.class)
                .single();
        if (ready == null || ready != 1) {
            throw StockException.conflict(
                    "Activate the order-book and automatic market "
                            + "before issue-underwriter supply: "
                            + symbol
            );
        }
    }

    private SupplyUsage findSupplyUsage(long contractId) {
        return jdbcClient.sql(
                        """
                        select coalesce(sum(submitted_quantity), 0) as submitted_quantity,
                               coalesce(sum(submitted_amount), 0) as submitted_amount
                          from stock_underwriting_daily_supply_state
                         where underwriting_contract_id = :contractId
                        """
                )
                .param("contractId", contractId)
                .query((rs, rowNum) -> new SupplyUsage(
                        rs.getLong("submitted_quantity"),
                        rs.getBigDecimal("submitted_amount")
                ))
                .single();
    }

    private void markDailyStateSuspended(
            long contractId,
            LocalDate simulationTradeDate,
            long cancelledOrderCount,
            long policyVersion,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                update stock_underwriting_daily_supply_state
                   set cancelled_order_count = cancelled_order_count + ?,
                       state_status = 'SUSPENDED',
                       gate_reason = 'ADMIN_SUSPENDED',
                       policy_version = ?,
                       version = version + 1,
                       updated_at = ?
                 where simulation_trade_date = ?
                   and underwriting_contract_id = ?
                """,
                cancelledOrderCount,
                policyVersion,
                now,
                simulationTradeDate,
                contractId
        );
    }

    private void insertPolicyVersion(
            ContractTarget target,
            long version,
            LocalDate effectiveBusinessDate,
            String status,
            BigDecimal supplyRate,
            Integer durationDays,
            long quantityLimit,
            BigDecimal amountLimit,
            String reason,
            String changedBy,
            LocalDateTime now
    ) {
        retireExistingPolicyVersions(target.contractCode(), now);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("preset", "INDEPENDENT_PASSIVE_UNDERWRITER_SUPPLY_V1");
        config.put("contractId", target.contractId());
        config.put("contractCode", target.contractCode());
        config.put("symbol", target.symbol());
        config.put("status", status);
        config.put("supplyRate", supplyRate);
        config.put("durationDays", durationDays);
        config.put("quantityLimit", quantityLimit);
        config.put("amountLimit", amountLimit);
        config.put("sellOnly", true);
        config.put("passiveOnly", true);
        config.put("cancellationRefundsSubmissionBudget", false);
        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Issue-underwriter policy JSON serialization failed",
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
                            'UNDERWRITING_CONTRACT', ?, ?, ?, 'ACTIVE', ?,
                            ?, ?, ?, ?
                        )
                        """,
                        target.contractCode(),
                        version,
                        effectiveBusinessDate,
                        configJson,
                        reason,
                        changedBy,
                        now,
                        now
                ),
                "Issue-underwriter policy version"
        );
    }

    private void retireExistingPolicyVersions(
            String contractCode,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set status = 'RETIRED',
                       updated_at = ?
                 where policy_scope = 'UNDERWRITING_CONTRACT'
                   and scope_key = ?
                   and status in ('DRAFT', 'SCHEDULED', 'ACTIVE')
                """,
                now,
                contractCode
        );
    }

    private BigDecimal normalizedSupplyRate(UnderwritingSupplyActivationRequest request) {
        BigDecimal value = request == null || request.supplyRate() == null
                ? DEFAULT_SUPPLY_RATE
                : request.supplyRate();
        if (value.compareTo(MIN_SUPPLY_RATE) < 0
                || value.compareTo(MAX_SUPPLY_RATE) > 0) {
            throw StockException.badRequest(
                    "Issue-underwriter supply rate must be between 0.01 and 0.25"
            );
        }
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private int normalizedDurationDays(UnderwritingSupplyActivationRequest request) {
        int value = request == null || request.durationDays() == null
                ? DEFAULT_DURATION_DAYS
                : request.durationDays();
        if (value < MIN_DURATION_DAYS || value > MAX_DURATION_DAYS) {
            throw StockException.badRequest(
                    "Issue-underwriter supply duration must be between 1 and 60 days"
            );
        }
        return value;
    }

    private String normalizeReason(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return truncate(normalized.isBlank() ? fallback : normalized, 500);
    }

    private String normalizeChangedBy(String value) {
        String normalized = value == null ? "" : value.trim();
        return truncate(normalized.isBlank() ? "SYSTEM" : normalized, 64);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void requirePositiveContractId(long contractId) {
        if (contractId <= 0L) {
            throw StockException.badRequest("Underwriting contract id must be positive");
        }
    }

    private void requireSingleUpdate(int updated, String operation) {
        if (updated != 1) {
            throw new IllegalStateException(
                    operation + " count mismatch: " + updated
            );
        }
    }

    private record ContractTarget(
            long contractId,
            String contractCode,
            String symbol,
            long participantId,
            long accountId,
            long totalIssueQuantity,
            long tradableAllocationQuantity,
            long lockedAllocationQuantity,
            long externalAllocationQuantity,
            long underwrittenQuantity,
            BigDecimal issuePrice,
            long stabilizationQuantityLimit,
            BigDecimal stabilizationAmountLimit,
            String status,
            long policyVersion
    ) {
    }

    private record RoleSnapshot(
            String accountStatus,
            String participantCategory,
            String accountSelfTradeGroupId,
            String participantType,
            String participantStatus,
            String participantSelfTradeGroupId,
            String accountRole,
            String roleMappingStatus,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long holdingQuantity,
            long reservedQuantity,
            long unmanagedHoldingCount,
            long nonContractOpenOrderCount,
            long openContractOrderCount,
            LocalDate businessDate
    ) {
    }

    private record SupplyUsage(long submittedQuantity, BigDecimal submittedAmount) {
    }

    private record SupplyReconciliation(
            long issuedShares,
            long tradableShares,
            long totalHoldingQuantity,
            long invalidHoldingCount,
            long initialLedgerQuantity,
            long initialTradableLedgerQuantity,
            long initialLockedLedgerQuantity
    ) {
        boolean matches(ContractTarget target) {
            try {
                return Math.addExact(
                        target.tradableAllocationQuantity(),
                        target.lockedAllocationQuantity()
                ) == target.totalIssueQuantity()
                        && Math.addExact(
                                target.externalAllocationQuantity(),
                                target.underwrittenQuantity()
                        ) == target.tradableAllocationQuantity()
                        && issuedShares >= target.totalIssueQuantity()
                        && tradableShares >= target.tradableAllocationQuantity()
                        && totalHoldingQuantity == issuedShares
                        && invalidHoldingCount == 0L
                        && initialLedgerQuantity == target.totalIssueQuantity()
                        && initialTradableLedgerQuantity
                        == target.tradableAllocationQuantity()
                        && initialLockedLedgerQuantity
                        == target.lockedAllocationQuantity();
            } catch (ArithmeticException ignored) {
                return false;
            }
        }
    }
}
