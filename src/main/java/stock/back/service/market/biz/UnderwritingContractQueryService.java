package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.SecurityAllocationResponse;
import stock.back.service.market.vo.UnderwritingContractResponse;

@Service
public class UnderwritingContractQueryService {

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);

    private final JdbcClient jdbcClient;

    public UnderwritingContractQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public List<UnderwritingContractResponse> getContracts() {
        List<ContractRow> contracts = jdbcClient.sql(
                        """
                        select contract.id as contract_id,
                               contract.contract_code,
                               contract.corporate_action_id,
                               contract.symbol,
                               instrument.name as instrument_name,
                               instrument.issued_shares,
                               instrument.tradable_shares as instrument_tradable_shares,
                               contract.total_issue_quantity,
                               contract.tradable_allocation_quantity,
                               contract.locked_allocation_quantity,
                               contract.external_allocation_quantity,
                               contract.underwritten_quantity,
                               contract.issue_price,
                               contract.underwriting_type,
                               contract.stabilization_start_date,
                               contract.stabilization_end_date,
                               contract.stabilization_quantity_limit,
                               contract.stabilization_amount_limit,
                               contract.status as contract_status,
                               contract.policy_version,
                               contract.created_at as contract_created_at,
                               contract.updated_at as contract_updated_at,
                               participant.id as participant_id,
                               participant.participant_code,
                               participant.display_name as participant_display_name,
                               participant.participant_type,
                               participant.status as participant_status,
                               participant.self_trade_group_id as participant_self_trade_group_id,
                               account.id as account_id,
                               account.account_code,
                               account.status as account_status,
                               account.participant_category,
                               account.self_trade_group_id as account_self_trade_group_id,
                               account.cash_balance,
                               role_mapping.account_role,
                               role_mapping.desk_code,
                               role_mapping.status as role_mapping_status,
                               role_mapping.effective_from as role_effective_from,
                               role_mapping.effective_to as role_effective_to,
                               coalesce(holding.quantity, 0) as holding_quantity,
                               coalesce(holding.reserved_quantity, 0) as reserved_sell_quantity,
                               coalesce(holding.average_price, 0) as average_price,
                               coalesce(price.current_price, contract.issue_price) as current_price,
                               (
                                   select count(*)
                                     from stock_order_strategy_origin strategy_origin
                                     join stock_order open_order
                                       on open_order.id = strategy_origin.order_id
                                    where strategy_origin.underwriting_contract_id = contract.id
                                      and strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                                      and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                                      and open_order.quantity > open_order.filled_quantity
                               ) as open_underwriting_order_count,
                               (
                                   select coalesce(sum(
                                              open_order.quantity - open_order.filled_quantity
                                          ), 0)
                                     from stock_order_strategy_origin strategy_origin
                                     join stock_order open_order
                                       on open_order.id = strategy_origin.order_id
                                    where strategy_origin.underwriting_contract_id = contract.id
                                      and strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                                      and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                                      and open_order.quantity > open_order.filled_quantity
                               ) as open_underwriting_order_quantity,
                               (
                                   select count(*)
                                     from stock_order open_order
                                    where open_order.account_id = contract.account_id
                                      and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                                      and open_order.quantity > open_order.filled_quantity
                                      and not exists (
                                          select 1
                                            from stock_order_strategy_origin strategy_origin
                                           where strategy_origin.order_id = open_order.id
                                             and strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                                             and strategy_origin.underwriting_contract_id = contract.id
                                      )
                               ) as non_contract_open_order_count,
                               (
                                   select count(*)
                                     from stock_holding unmanaged_holding
                                    where unmanaged_holding.account_id = contract.account_id
                                      and unmanaged_holding.symbol <> contract.symbol
                                      and (
                                          unmanaged_holding.quantity > 0
                                          or unmanaged_holding.reserved_quantity > 0
                                      )
                               ) as unmanaged_holding_count,
                               (
                                   select coalesce(sum(symbol_holding.quantity), 0)
                                     from stock_holding symbol_holding
                                    where symbol_holding.symbol = contract.symbol
                               ) as current_total_holding_quantity,
                               (
                                   select count(*)
                                     from stock_holding invalid_holding
                                    where invalid_holding.symbol = contract.symbol
                                      and (
                                          invalid_holding.quantity < 0
                                          or invalid_holding.reserved_quantity < 0
                                          or invalid_holding.reserved_quantity
                                             > invalid_holding.quantity
                                      )
                               ) as invalid_holding_count
                          from stock_underwriting_contract contract
                          join stock_order_book_instrument instrument
                            on instrument.symbol = contract.symbol
                          join stock_market_participant participant
                            on participant.id = contract.participant_id
                          join stock_account account
                            on account.id = contract.account_id
                          left join stock_market_participant_account role_mapping
                            on role_mapping.participant_id = contract.participant_id
                           and role_mapping.account_id = contract.account_id
                          left join stock_holding holding
                            on holding.account_id = contract.account_id
                           and holding.symbol = contract.symbol
                          left join stock_price price
                            on price.symbol = contract.symbol
                         order by contract.symbol, contract.id
                        """
                )
                .query(this::mapContractRow)
                .list();
        if (contracts.isEmpty()) {
            return List.of();
        }

        Map<Long, List<SecurityAllocationResponse>> allocationsByContract = new LinkedHashMap<>();
        for (SecurityAllocationResponse allocation : getAllocations()) {
            if (allocation.underwritingContractId() != null) {
                allocationsByContract
                        .computeIfAbsent(allocation.underwritingContractId(), ignored -> new ArrayList<>())
                        .add(allocation);
            }
        }
        Map<Long, SupplyAudit> supplyAudits = getSupplyAudits();

        return contracts.stream()
                .map(contract -> toResponse(
                        contract,
                        allocationsByContract.getOrDefault(contract.contractId(), List.of()),
                        supplyAudits.getOrDefault(contract.contractId(), SupplyAudit.EMPTY)
                ))
                .toList();
    }

    /*
     * Mutation endpoints use this method for their immediate response. Keep the
     * read on the master route so replica lag cannot return the pre-transition
     * contract state to the admin UI.
     */
    @Transactional
    public UnderwritingContractResponse getContract(long contractId) {
        if (contractId <= 0L) {
            throw StockException.badRequest("Underwriting contract id must be positive");
        }
        return getContracts().stream()
                .filter(contract -> contract.contractId() == contractId)
                .findFirst()
                .orElseThrow(() -> StockException.notFound(
                        "Unknown underwriting contract: " + contractId
                ));
    }

    private Map<Long, SupplyAudit> getSupplyAudits() {
        Map<Long, SupplyTotals> totals = new LinkedHashMap<>();
        jdbcClient.sql(
                        """
                        select underwriting_contract_id,
                               coalesce(sum(submitted_quantity), 0) as submitted_quantity,
                               coalesce(sum(submitted_amount), 0) as submitted_amount,
                               coalesce(sum(generated_order_count), 0) as generated_order_count,
                               coalesce(sum(cancelled_order_count), 0) as cancelled_order_count
                          from stock_underwriting_daily_supply_state
                         group by underwriting_contract_id
                        """
                )
                .query((rs, rowNum) -> new SupplyTotals(
                        rs.getLong("underwriting_contract_id"),
                        rs.getLong("submitted_quantity"),
                        money(rs.getBigDecimal("submitted_amount")),
                        rs.getLong("generated_order_count"),
                        rs.getLong("cancelled_order_count")
                ))
                .list()
                .forEach(row -> totals.put(row.contractId(), row));

        Map<Long, SupplyExecution> executions = new LinkedHashMap<>();
        jdbcClient.sql(
                        """
                        select strategy_origin.underwriting_contract_id,
                               coalesce(sum(execution.quantity), 0) as executed_quantity,
                               coalesce(sum(execution.gross_amount), 0) as executed_amount
                          from stock_order_strategy_origin strategy_origin
                          join stock_execution execution
                            on execution.order_id = strategy_origin.order_id
                         where strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                           and strategy_origin.underwriting_contract_id is not null
                         group by strategy_origin.underwriting_contract_id
                        """
                )
                .query((rs, rowNum) -> new SupplyExecution(
                        rs.getLong("underwriting_contract_id"),
                        rs.getLong("executed_quantity"),
                        money(rs.getBigDecimal("executed_amount"))
                ))
                .list()
                .forEach(row -> executions.put(row.contractId(), row));

        Map<Long, SupplyDailyState> latestStates = new LinkedHashMap<>();
        jdbcClient.sql(
                        """
                        select state.underwriting_contract_id,
                               state.simulation_trade_date,
                               state.reference_daily_volume,
                               state.submission_quantity_limit,
                               state.submission_amount_limit,
                               state.submitted_quantity,
                               state.submitted_amount,
                               state.generated_order_count,
                               state.cancelled_order_count,
                               state.last_order_price,
                               state.state_status,
                               state.gate_reason,
                               state.policy_version,
                               state.updated_at
                          from stock_underwriting_daily_supply_state state
                          join (
                              select underwriting_contract_id,
                                     max(simulation_trade_date) as simulation_trade_date
                                from stock_underwriting_daily_supply_state
                               group by underwriting_contract_id
                          ) latest
                            on latest.underwriting_contract_id =
                               state.underwriting_contract_id
                           and latest.simulation_trade_date =
                               state.simulation_trade_date
                         order by state.underwriting_contract_id
                        """
                )
                .query((rs, rowNum) -> new SupplyDailyState(
                        rs.getLong("underwriting_contract_id"),
                        rs.getObject("simulation_trade_date", LocalDate.class),
                        rs.getLong("reference_daily_volume"),
                        rs.getLong("submission_quantity_limit"),
                        money(rs.getBigDecimal("submission_amount_limit")),
                        rs.getLong("submitted_quantity"),
                        money(rs.getBigDecimal("submitted_amount")),
                        rs.getLong("generated_order_count"),
                        rs.getLong("cancelled_order_count"),
                        nullableMoney(rs.getBigDecimal("last_order_price")),
                        rs.getString("state_status"),
                        rs.getString("gate_reason"),
                        rs.getLong("policy_version"),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .list()
                .forEach(row -> latestStates.put(row.contractId(), row));

        Map<Long, SupplyAudit> audits = new LinkedHashMap<>();
        totals.forEach((contractId, total) -> audits.put(
                contractId,
                new SupplyAudit(
                        total.submittedQuantity(),
                        total.submittedAmount(),
                        executions.getOrDefault(contractId, SupplyExecution.EMPTY)
                                .executedQuantity(),
                        executions.getOrDefault(contractId, SupplyExecution.EMPTY)
                                .executedAmount(),
                        total.generatedOrderCount(),
                        total.cancelledOrderCount(),
                        latestStates.get(contractId)
                )
        ));
        executions.forEach((contractId, execution) -> audits.putIfAbsent(
                contractId,
                new SupplyAudit(
                        0L,
                        ZERO_MONEY,
                        execution.executedQuantity(),
                        execution.executedAmount(),
                        0L,
                        0L,
                        latestStates.get(contractId)
                )
        ));
        latestStates.forEach((contractId, state) -> audits.putIfAbsent(
                contractId,
                new SupplyAudit(0L, ZERO_MONEY, 0L, ZERO_MONEY, 0L, 0L, state)
        ));
        return Map.copyOf(audits);
    }

    private List<SecurityAllocationResponse> getAllocations() {
        return jdbcClient.sql(
                        """
                        select allocation.id as allocation_id,
                               allocation.idempotency_key,
                               allocation.event_type,
                               allocation.corporate_action_id,
                               allocation.underwriting_contract_id,
                               allocation.source_account_id,
                               allocation.destination_account_id,
                               destination.account_code as destination_account_code,
                               destination.participant_category as destination_participant_category,
                               allocation.symbol,
                               allocation.quantity,
                               allocation.unit_price,
                               allocation.allocation_reason,
                               allocation.tradability_status,
                               allocation.effective_business_date,
                               allocation.unlock_business_date,
                               allocation.created_at,
                               coalesce(holding.quantity, 0) as current_holding_quantity,
                               coalesce(holding.reserved_quantity, 0) as current_reserved_quantity,
                               coalesce(holding.average_price, 0) as current_average_price
                          from stock_security_allocation_ledger allocation
                          join stock_account destination
                            on destination.id = allocation.destination_account_id
                          left join stock_holding holding
                            on holding.account_id = allocation.destination_account_id
                           and holding.symbol = allocation.symbol
                         where allocation.underwriting_contract_id is not null
                         order by allocation.underwriting_contract_id, allocation.id
                        """
                )
                .query(this::mapAllocation)
                .list();
    }

    private ContractRow mapContractRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ContractRow(
                resultSet.getLong("contract_id"),
                resultSet.getString("contract_code"),
                nullableLong(resultSet, "corporate_action_id"),
                resultSet.getString("symbol"),
                resultSet.getString("instrument_name"),
                resultSet.getLong("issued_shares"),
                resultSet.getLong("instrument_tradable_shares"),
                resultSet.getLong("total_issue_quantity"),
                resultSet.getLong("tradable_allocation_quantity"),
                resultSet.getLong("locked_allocation_quantity"),
                resultSet.getLong("external_allocation_quantity"),
                resultSet.getLong("underwritten_quantity"),
                money(resultSet.getBigDecimal("issue_price")),
                resultSet.getString("underwriting_type"),
                resultSet.getObject("stabilization_start_date", LocalDate.class),
                resultSet.getObject("stabilization_end_date", LocalDate.class),
                resultSet.getLong("stabilization_quantity_limit"),
                money(resultSet.getBigDecimal("stabilization_amount_limit")),
                resultSet.getString("contract_status"),
                resultSet.getLong("policy_version"),
                resultSet.getObject("contract_created_at", LocalDateTime.class),
                resultSet.getObject("contract_updated_at", LocalDateTime.class),
                resultSet.getLong("participant_id"),
                resultSet.getString("participant_code"),
                resultSet.getString("participant_display_name"),
                resultSet.getString("participant_type"),
                resultSet.getString("participant_status"),
                resultSet.getString("participant_self_trade_group_id"),
                resultSet.getLong("account_id"),
                resultSet.getString("account_code"),
                resultSet.getString("account_status"),
                resultSet.getString("participant_category"),
                resultSet.getString("account_self_trade_group_id"),
                money(resultSet.getBigDecimal("cash_balance")),
                resultSet.getString("account_role"),
                resultSet.getString("desk_code"),
                resultSet.getString("role_mapping_status"),
                resultSet.getObject("role_effective_from", LocalDate.class),
                resultSet.getObject("role_effective_to", LocalDate.class),
                resultSet.getLong("holding_quantity"),
                resultSet.getLong("reserved_sell_quantity"),
                money(resultSet.getBigDecimal("average_price")),
                money(resultSet.getBigDecimal("current_price")),
                resultSet.getLong("open_underwriting_order_count"),
                resultSet.getLong("open_underwriting_order_quantity"),
                resultSet.getLong("non_contract_open_order_count"),
                resultSet.getLong("unmanaged_holding_count"),
                resultSet.getLong("current_total_holding_quantity"),
                resultSet.getLong("invalid_holding_count")
        );
    }

    private SecurityAllocationResponse mapAllocation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SecurityAllocationResponse(
                resultSet.getLong("allocation_id"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("event_type"),
                nullableLong(resultSet, "corporate_action_id"),
                nullableLong(resultSet, "underwriting_contract_id"),
                nullableLong(resultSet, "source_account_id"),
                resultSet.getLong("destination_account_id"),
                resultSet.getString("destination_account_code"),
                resultSet.getString("destination_participant_category"),
                resultSet.getString("symbol"),
                resultSet.getLong("quantity"),
                money(resultSet.getBigDecimal("unit_price")),
                resultSet.getString("allocation_reason"),
                resultSet.getString("tradability_status"),
                resultSet.getObject("effective_business_date", LocalDate.class),
                resultSet.getObject("unlock_business_date", LocalDate.class),
                resultSet.getLong("current_holding_quantity"),
                resultSet.getLong("current_reserved_quantity"),
                money(resultSet.getBigDecimal("current_average_price")),
                resultSet.getObject("created_at", LocalDateTime.class)
        );
    }

    private UnderwritingContractResponse toResponse(
            ContractRow contract,
            List<SecurityAllocationResponse> allocations,
            SupplyAudit supplyAudit
    ) {
        long initialLedgerQuantity = initialAllocationQuantity(allocations, null);
        long initialTradableQuantity = initialAllocationQuantity(allocations, "TRADABLE");
        long initialLockedQuantity = initialAllocationQuantity(allocations, "LOCKED");
        boolean contractQuantityBalanced =
                contract.tradableAllocationQuantity() + contract.lockedAllocationQuantity()
                        == contract.totalIssueQuantity()
                        && contract.externalAllocationQuantity() + contract.underwrittenQuantity()
                        == contract.tradableAllocationQuantity();
        boolean instrumentQuantityCovered =
                contract.issuedShares() >= contract.totalIssueQuantity()
                        && contract.instrumentTradableShares() >= contract.tradableAllocationQuantity();
        boolean allocationLedgerMatched =
                initialLedgerQuantity == contract.totalIssueQuantity()
                        && initialTradableQuantity == contract.tradableAllocationQuantity()
                        && initialLockedQuantity == contract.lockedAllocationQuantity();
        boolean holdingSupplyMatched =
                contract.currentTotalHoldingQuantity() == contract.issuedShares()
                        && contract.invalidHoldingCount() == 0L;
        List<String> issues = reconciliationIssues(
                contract,
                contractQuantityBalanced,
                instrumentQuantityCovered,
                allocationLedgerMatched,
                holdingSupplyMatched
        );
        boolean roleEligible = issues.stream().noneMatch(issue -> issue.startsWith("ROLE_"));
        long availableSellQuantity = Math.max(
                0L,
                contract.holdingQuantity() - contract.reservedSellQuantity()
        );
        BigDecimal holdingMarketValue = contract.currentPrice()
                .multiply(BigDecimal.valueOf(contract.holdingQuantity()))
                .setScale(2, RoundingMode.HALF_UP);

        return new UnderwritingContractResponse(
                contract.contractId(),
                contract.contractCode(),
                contract.corporateActionId(),
                contract.symbol(),
                contract.instrumentName(),
                contract.issuedShares(),
                contract.instrumentTradableShares(),
                contract.totalIssueQuantity(),
                contract.tradableAllocationQuantity(),
                contract.lockedAllocationQuantity(),
                contract.externalAllocationQuantity(),
                contract.underwrittenQuantity(),
                rate(contract.tradableAllocationQuantity(), contract.totalIssueQuantity()),
                contract.issuePrice(),
                contract.underwritingType(),
                contract.stabilizationStartDate(),
                contract.stabilizationEndDate(),
                contract.stabilizationQuantityLimit(),
                contract.stabilizationAmountLimit(),
                contract.status(),
                contract.policyVersion(),
                new UnderwritingContractResponse.Account(
                        contract.participantId(),
                        contract.participantCode(),
                        contract.participantDisplayName(),
                        contract.participantType(),
                        contract.participantStatus(),
                        contract.participantSelfTradeGroupId(),
                        contract.accountId(),
                        contract.accountCode(),
                        contract.accountStatus(),
                        contract.participantCategory(),
                        contract.accountSelfTradeGroupId(),
                        contract.accountRole(),
                        contract.deskCode(),
                        contract.roleMappingStatus(),
                        contract.roleEffectiveFrom(),
                        contract.roleEffectiveTo(),
                        contract.cashBalance(),
                        contract.holdingQuantity(),
                        contract.reservedSellQuantity(),
                        availableSellQuantity,
                        contract.averagePrice(),
                        contract.currentPrice(),
                        holdingMarketValue,
                        contract.openUnderwritingOrderCount(),
                        contract.openUnderwritingOrderQuantity(),
                        contract.nonContractOpenOrderCount(),
                        contract.unmanagedHoldingCount()
                ),
                new UnderwritingContractResponse.Supply(
                        rate(
                                contract.stabilizationQuantityLimit(),
                                contract.underwrittenQuantity()
                        ),
                        supplyAudit.submittedQuantity(),
                        supplyAudit.submittedAmount(),
                        supplyAudit.executedQuantity(),
                        supplyAudit.executedAmount(),
                        Math.max(
                                0L,
                                contract.stabilizationQuantityLimit()
                                        - supplyAudit.submittedQuantity()
                        ),
                        nonNegativeMoney(
                                contract.stabilizationAmountLimit()
                                        .subtract(supplyAudit.submittedAmount())
                        ),
                        supplyAudit.generatedOrderCount(),
                        supplyAudit.cancelledOrderCount(),
                        toDailyStateResponse(supplyAudit.latestDailyState())
                ),
                new UnderwritingContractResponse.Reconciliation(
                        initialLedgerQuantity,
                        initialTradableQuantity,
                        initialLockedQuantity,
                        contract.currentTotalHoldingQuantity(),
                        contractQuantityBalanced,
                        instrumentQuantityCovered,
                        allocationLedgerMatched,
                        holdingSupplyMatched,
                        roleEligible,
                        issues
                ),
                List.copyOf(allocations),
                contract.createdAt(),
                contract.updatedAt()
        );
    }

    private UnderwritingContractResponse.DailyState toDailyStateResponse(
            SupplyDailyState state
    ) {
        if (state == null) {
            return null;
        }
        return new UnderwritingContractResponse.DailyState(
                state.simulationTradeDate(),
                state.referenceDailyVolume(),
                state.submissionQuantityLimit(),
                state.submissionAmountLimit(),
                state.submittedQuantity(),
                state.submittedAmount(),
                state.generatedOrderCount(),
                state.cancelledOrderCount(),
                state.lastOrderPrice(),
                state.stateStatus(),
                state.gateReason(),
                state.policyVersion(),
                state.updatedAt()
        );
    }

    private long initialAllocationQuantity(
            List<SecurityAllocationResponse> allocations,
            String tradabilityStatus
    ) {
        return allocations.stream()
                .filter(allocation -> "INITIAL_ISSUE".equals(allocation.eventType()))
                .filter(allocation -> allocation.sourceAccountId() == null)
                .filter(allocation -> tradabilityStatus == null
                        || tradabilityStatus.equals(allocation.tradabilityStatus()))
                .mapToLong(SecurityAllocationResponse::quantity)
                .sum();
    }

    private List<String> reconciliationIssues(
            ContractRow contract,
            boolean contractQuantityBalanced,
            boolean instrumentQuantityCovered,
            boolean allocationLedgerMatched,
            boolean holdingSupplyMatched
    ) {
        List<String> issues = new ArrayList<>();
        if (!"ISSUE_UNDERWRITER".equals(contract.participantType())) {
            issues.add("ROLE_PARTICIPANT_TYPE_MISMATCH");
        }
        if (!"ACTIVE".equals(contract.participantStatus())) {
            issues.add("ROLE_PARTICIPANT_NOT_ACTIVE");
        }
        if (!roleLifecycleStatusEligible(contract.status(), contract.accountStatus())) {
            issues.add("ROLE_ACCOUNT_NOT_ACTIVE");
        }
        if (!"ISSUE_UNDERWRITER".equals(contract.participantCategory())) {
            issues.add("ROLE_ACCOUNT_CATEGORY_MISMATCH");
        }
        if (!"ISSUE_UNDERWRITER".equals(contract.accountRole())
                || !roleLifecycleStatusEligible(
                        contract.status(),
                        contract.roleMappingStatus()
                )) {
            issues.add("ROLE_MAPPING_MISMATCH");
        }
        if (contract.participantSelfTradeGroupId() == null
                || !contract.participantSelfTradeGroupId().equals(contract.accountSelfTradeGroupId())) {
            issues.add("ROLE_SELF_TRADE_GROUP_MISMATCH");
        }
        if (contract.nonContractOpenOrderCount() > 0L) {
            issues.add("ROLE_NON_CONTRACT_OPEN_ORDER");
        }
        if (contract.unmanagedHoldingCount() > 0L) {
            issues.add("ROLE_UNMANAGED_HOLDING");
        }
        if (!contractQuantityBalanced) {
            issues.add("CONTRACT_QUANTITY_MISMATCH");
        }
        if (!instrumentQuantityCovered) {
            issues.add("INSTRUMENT_QUANTITY_UNDERFLOW");
        }
        if (!allocationLedgerMatched) {
            issues.add("ALLOCATION_LEDGER_MISMATCH");
        }
        if (!holdingSupplyMatched) {
            issues.add("HOLDING_SUPPLY_MISMATCH");
        }
        if (contract.invalidHoldingCount() > 0L) {
            issues.add("HOLDING_RESERVATION_INVALID");
        }
        return List.copyOf(issues);
    }

    private boolean roleLifecycleStatusEligible(
            String contractStatus,
            String roleStatus
    ) {
        if ("ACTIVE".equals(roleStatus)) {
            return true;
        }
        return ("COMPLETED".equals(contractStatus)
                || "CANCELLED".equals(contractStatus))
                && "CLOSED".equals(roleStatus);
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0L) {
            return BigDecimal.ZERO.setScale(6);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? ZERO_MONEY : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nonNegativeMoney(BigDecimal value) {
        return value.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private record SupplyTotals(
            long contractId,
            long submittedQuantity,
            BigDecimal submittedAmount,
            long generatedOrderCount,
            long cancelledOrderCount
    ) {
    }

    private record SupplyExecution(
            long contractId,
            long executedQuantity,
            BigDecimal executedAmount
    ) {
        private static final SupplyExecution EMPTY =
                new SupplyExecution(0L, 0L, ZERO_MONEY);
    }

    private record SupplyDailyState(
            long contractId,
            LocalDate simulationTradeDate,
            long referenceDailyVolume,
            long submissionQuantityLimit,
            BigDecimal submissionAmountLimit,
            long submittedQuantity,
            BigDecimal submittedAmount,
            long generatedOrderCount,
            long cancelledOrderCount,
            BigDecimal lastOrderPrice,
            String stateStatus,
            String gateReason,
            long policyVersion,
            LocalDateTime updatedAt
    ) {
    }

    private record SupplyAudit(
            long submittedQuantity,
            BigDecimal submittedAmount,
            long executedQuantity,
            BigDecimal executedAmount,
            long generatedOrderCount,
            long cancelledOrderCount,
            SupplyDailyState latestDailyState
    ) {
        private static final SupplyAudit EMPTY =
                new SupplyAudit(0L, ZERO_MONEY, 0L, ZERO_MONEY, 0L, 0L, null);
    }

    private record ContractRow(
            long contractId,
            String contractCode,
            Long corporateActionId,
            String symbol,
            String instrumentName,
            long issuedShares,
            long instrumentTradableShares,
            long totalIssueQuantity,
            long tradableAllocationQuantity,
            long lockedAllocationQuantity,
            long externalAllocationQuantity,
            long underwrittenQuantity,
            BigDecimal issuePrice,
            String underwritingType,
            LocalDate stabilizationStartDate,
            LocalDate stabilizationEndDate,
            long stabilizationQuantityLimit,
            BigDecimal stabilizationAmountLimit,
            String status,
            long policyVersion,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            long participantId,
            String participantCode,
            String participantDisplayName,
            String participantType,
            String participantStatus,
            String participantSelfTradeGroupId,
            long accountId,
            String accountCode,
            String accountStatus,
            String participantCategory,
            String accountSelfTradeGroupId,
            BigDecimal cashBalance,
            String accountRole,
            String deskCode,
            String roleMappingStatus,
            LocalDate roleEffectiveFrom,
            LocalDate roleEffectiveTo,
            long holdingQuantity,
            long reservedSellQuantity,
            BigDecimal averagePrice,
            BigDecimal currentPrice,
            long openUnderwritingOrderCount,
            long openUnderwritingOrderQuantity,
            long nonContractOpenOrderCount,
            long unmanagedHoldingCount,
            long currentTotalHoldingQuantity,
            long invalidHoldingCount
    ) {
    }
}
