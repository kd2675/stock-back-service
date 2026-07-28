package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.UnderwritingContractCreateRequest;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
public class UnderwritingContractProvisionService {

    private static final String PARTICIPANT_CODE = "DEFAULT_ISSUE_UNDERWRITER";
    private static final String PARTICIPANT_TYPE = "ISSUE_UNDERWRITER";
    private static final String SELF_TRADE_GROUP_ID = "ISSUE_UNDERWRITER:DEFAULT";

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    public UnderwritingContractProvisionService(
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService,
            MarketLedgerFreezeGuard marketLedgerFreezeGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationClockService = simulationClockService;
        this.marketLedgerFreezeGuard = marketLedgerFreezeGuard;
    }

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public long createContract(
            String symbol,
            UnderwritingContractCreateRequest request,
            String changedBy
    ) {
        String normalizedSymbol = normalizedSymbol(symbol);
        String underwritingType = normalizedUnderwritingType(request);
        String changeReason = normalizeReason(request);
        String normalizedChangedBy = normalizeChangedBy(changedBy);
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDate businessDate = marketLedgerFreezeGuard.acquireJdbcMutationPermit(
                "independent underwriting contract creation"
        );
        requireNoExistingContract(normalizedSymbol);
        PendingAllocation allocation = lockPendingAllocation(normalizedSymbol);
        MarketParticipant participant = requireParticipant();
        LocalDateTime now = clock.simulationDateTime();

        long underwriterAccountId = insertAccount(normalizedSymbol, now);
        insertParticipantAccount(
                participant.id(),
                underwriterAccountId,
                normalizedSymbol,
                businessDate,
                now
        );
        long contractId = insertContract(
                allocation,
                participant.id(),
                underwriterAccountId,
                underwritingType,
                now
        );
        transferFloatHolding(
                allocation,
                underwriterAccountId,
                now
        );
        finalizeInitialAllocation(
                allocation,
                contractId,
                underwriterAccountId
        );
        insertTransferAudit(
                allocation,
                contractId,
                underwriterAccountId,
                businessDate,
                now
        );
        insertPolicyVersion(
                allocation,
                contractId,
                underwritingType,
                businessDate,
                changeReason,
                normalizedChangedBy,
                now
        );
        verifyIssuedShareReconciliation(
                normalizedSymbol,
                allocation.issuedShares()
        );
        return contractId;
    }

    private void requireNoExistingContract(String symbol) {
        Long existingId = jdbcClient.sql(
                        """
                        select id
                          from stock_underwriting_contract
                         where symbol = ?
                         order by id
                         limit 1
                         for update
                        """
                )
                .param(symbol)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (existingId != null) {
            throw StockException.conflict(
                    "An underwriting contract already exists for " + symbol
            );
        }
    }

    private PendingAllocation lockPendingAllocation(String symbol) {
        return jdbcClient.sql(
                        """
                        select instrument.symbol,
                               instrument.issued_shares,
                               instrument.tradable_shares,
                               instrument.initial_price,
                               market.enabled as market_enabled,
                               market.market_status,
                               allocation.id as float_allocation_id,
                               allocation.corporate_action_id,
                               allocation.destination_account_id
                                   as float_custody_account_id,
                               holding.quantity as float_quantity,
                               holding.reserved_quantity as float_reserved_quantity,
                               holding.average_price as float_average_price,
                               locked_allocation.id as locked_allocation_id
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                          join stock_security_allocation_ledger allocation
                            on allocation.symbol = instrument.symbol
                           and allocation.event_type = 'INITIAL_ISSUE'
                           and allocation.source_account_id is null
                           and allocation.underwriting_contract_id is null
                           and allocation.allocation_reason =
                               'INITIAL_FLOAT_CUSTODY'
                           and allocation.tradability_status = 'TRADABLE'
                          join stock_holding holding
                            on holding.account_id =
                               allocation.destination_account_id
                           and holding.symbol = instrument.symbol
                          join stock_security_allocation_ledger locked_allocation
                            on locked_allocation.corporate_action_id =
                               allocation.corporate_action_id
                           and locked_allocation.symbol = instrument.symbol
                           and locked_allocation.event_type = 'INITIAL_ISSUE'
                           and locked_allocation.source_account_id is null
                           and locked_allocation.underwriting_contract_id is null
                           and locked_allocation.allocation_reason =
                               'INITIAL_LOCKED_CUSTODY'
                           and locked_allocation.tradability_status = 'LOCKED'
                         where instrument.symbol = ?
                           and instrument.enabled = true
                         for update
                        """
                )
                .param(symbol)
                .query((rs, rowNum) -> new PendingAllocation(
                        rs.getString("symbol"),
                        rs.getLong("issued_shares"),
                        rs.getLong("tradable_shares"),
                        rs.getBigDecimal("initial_price"),
                        rs.getBoolean("market_enabled"),
                        rs.getString("market_status"),
                        rs.getLong("float_allocation_id"),
                        rs.getLong("corporate_action_id"),
                        rs.getLong("float_custody_account_id"),
                        rs.getLong("float_quantity"),
                        rs.getLong("float_reserved_quantity"),
                        rs.getBigDecimal("float_average_price"),
                        rs.getLong("locked_allocation_id")
                ))
                .optional()
                .filter(PendingAllocation::pendingMarket)
                .filter(PendingAllocation::floatQuantityEligible)
                .orElseThrow(() -> StockException.conflict(
                        "The symbol needs a complete pending issuance allocation before "
                                + "an underwriting contract can be created: " + symbol
                ));
    }

    private MarketParticipant requireParticipant() {
        return jdbcClient.sql(
                        """
                        select id, participant_type, status, self_trade_group_id
                          from stock_market_participant
                         where participant_code = ?
                         for update
                        """
                )
                .param(PARTICIPANT_CODE)
                .query((rs, rowNum) -> new MarketParticipant(
                        rs.getLong("id"),
                        rs.getString("participant_type"),
                        rs.getString("status"),
                        rs.getString("self_trade_group_id")
                ))
                .optional()
                .filter(participant -> PARTICIPANT_TYPE.equals(
                        participant.participantType()
                ))
                .filter(participant -> "ACTIVE".equals(participant.status()))
                .filter(participant -> SELF_TRADE_GROUP_ID.equals(
                        participant.selfTradeGroupId()
                ))
                .orElseThrow(() -> StockException.conflict(
                        "Default issue-underwriter participant is missing or inconsistent"
                ));
    }

    private long insertAccount(String symbol, LocalDateTime now) {
        String userKey = "stock-issue-underwriter-" + symbol.toLowerCase(Locale.ROOT);
        return insertWithGeneratedKey(
                """
                insert into stock_account(
                    user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (?, ?, 'ACTIVE', 'ISSUE_UNDERWRITER', ?, 0.00, ?, ?)
                """,
                statement -> {
                    statement.setString(1, userKey);
                    statement.setString(2, "UW-" + symbol);
                    statement.setString(3, SELF_TRADE_GROUP_ID);
                    statement.setObject(4, now);
                    statement.setObject(5, now);
                }
        );
    }

    private void insertParticipantAccount(
            long participantId,
            long accountId,
            String symbol,
            LocalDate businessDate,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_market_participant_account(
                            participant_id, account_id, account_role, desk_code,
                            effective_from, effective_to, status,
                            created_at, updated_at
                        ) values (
                            ?, ?, 'ISSUE_UNDERWRITER', ?,
                            ?, null, 'ACTIVE', ?, ?
                        )
                        """,
                        participantId,
                        accountId,
                        symbol,
                        businessDate,
                        now,
                        now
                ),
                "Issue-underwriter account-role mapping"
        );
    }

    private long insertContract(
            PendingAllocation allocation,
            long participantId,
            long accountId,
            String underwritingType,
            LocalDateTime now
    ) {
        return insertWithGeneratedKey(
                """
                insert into stock_underwriting_contract(
                    contract_code, corporate_action_id, symbol,
                    participant_id, account_id,
                    total_issue_quantity, tradable_allocation_quantity,
                    locked_allocation_quantity, external_allocation_quantity,
                    underwritten_quantity, issue_price, underwriting_type,
                    stabilization_start_date, stabilization_end_date,
                    stabilization_quantity_limit, stabilization_amount_limit,
                    status, policy_version, created_at, updated_at
                ) values (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, 0,
                    ?, ?, ?,
                    null, null, 0, 0.00,
                    'ALLOCATED', 1, ?, ?
                )
                """,
                statement -> {
                    int index = 1;
                    statement.setString(index++, "INITIAL-ISSUE:" + allocation.symbol());
                    statement.setLong(index++, allocation.corporateActionId());
                    statement.setString(index++, allocation.symbol());
                    statement.setLong(index++, participantId);
                    statement.setLong(index++, accountId);
                    statement.setLong(index++, allocation.issuedShares());
                    statement.setLong(index++, allocation.tradableShares());
                    statement.setLong(
                            index++,
                            allocation.issuedShares() - allocation.tradableShares()
                    );
                    statement.setLong(index++, allocation.tradableShares());
                    statement.setBigDecimal(index++, allocation.issuePrice());
                    statement.setString(index++, underwritingType);
                    statement.setObject(index++, now);
                    statement.setObject(index, now);
                }
        );
    }

    private void transferFloatHolding(
            PendingAllocation allocation,
            long destinationAccountId,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_holding
                           set quantity = quantity - ?,
                               updated_at = ?
                         where account_id = ?
                           and symbol = ?
                           and reserved_quantity = 0
                           and quantity >= ?
                        """,
                        allocation.tradableShares(),
                        now,
                        allocation.floatCustodyAccountId(),
                        allocation.symbol(),
                        allocation.tradableShares()
                ),
                "Issue-underwriter source inventory transfer"
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_holding(
                            account_id, symbol, quantity, reserved_quantity,
                            average_price, updated_at
                        ) values (?, ?, ?, 0, ?, ?)
                        """,
                        destinationAccountId,
                        allocation.symbol(),
                        allocation.tradableShares(),
                        allocation.floatAveragePrice(),
                        now
                ),
                "Issue-underwriter destination inventory transfer"
        );
    }

    private void finalizeInitialAllocation(
            PendingAllocation allocation,
            long contractId,
            long underwriterAccountId
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_security_allocation_ledger
                           set underwriting_contract_id = ?,
                               destination_account_id = ?,
                               allocation_reason = 'INITIAL_FLOAT_UNDERWRITER'
                         where id = ?
                           and underwriting_contract_id is null
                           and destination_account_id = ?
                           and allocation_reason = 'INITIAL_FLOAT_CUSTODY'
                        """,
                        contractId,
                        underwriterAccountId,
                        allocation.floatAllocationId(),
                        allocation.floatCustodyAccountId()
                ),
                "Initial float allocation finalization"
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_security_allocation_ledger
                           set underwriting_contract_id = ?
                         where id = ?
                           and underwriting_contract_id is null
                           and allocation_reason = 'INITIAL_LOCKED_CUSTODY'
                        """,
                        contractId,
                        allocation.lockedAllocationId()
                ),
                "Initial locked allocation contract link"
        );
    }

    private void insertTransferAudit(
            PendingAllocation allocation,
            long contractId,
            long destinationAccountId,
            LocalDate businessDate,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_security_allocation_ledger(
                            idempotency_key, event_type, corporate_action_id,
                            underwriting_contract_id, source_account_id,
                            destination_account_id, symbol, quantity, unit_price,
                            allocation_reason, tradability_status,
                            effective_business_date, unlock_business_date,
                            created_at
                        ) values (
                            ?, 'MANUAL_REALLOCATION', ?, ?, ?,
                            ?, ?, ?, ?,
                            'UNSOLD_UNDERWRITING', 'TRADABLE',
                            ?, null, ?
                        )
                        """,
                        "UNDERWRITER-ALLOCATION:" + allocation.symbol(),
                        allocation.corporateActionId(),
                        contractId,
                        allocation.floatCustodyAccountId(),
                        destinationAccountId,
                        allocation.symbol(),
                        allocation.tradableShares(),
                        allocation.floatAveragePrice(),
                        businessDate,
                        now
                ),
                "Issue-underwriter allocation audit"
        );
    }

    private void insertPolicyVersion(
            PendingAllocation allocation,
            long contractId,
            String underwritingType,
            LocalDate businessDate,
            String changeReason,
            String changedBy,
            LocalDateTime now
    ) {
        String contractCode = "INITIAL-ISSUE:" + allocation.symbol();
        String configJson = """
                {
                  "preset": "INDEPENDENT_PASSIVE_UNDERWRITER_V1",
                  "contractId": %d,
                  "contractCode": "%s",
                  "symbol": "%s",
                  "underwritingType": "%s",
                  "status": "ALLOCATED",
                  "quantityLimit": 0,
                  "amountLimit": 0.00,
                  "sellOnly": true,
                  "passiveOnly": true,
                  "cancellationRefundsSubmissionBudget": false
                }
                """.formatted(
                contractId,
                contractCode,
                allocation.symbol(),
                underwritingType
        ).trim();
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_market_policy_version(
                            policy_scope, scope_key, version_no,
                            effective_business_date, status, config_json,
                            change_reason, changed_by, created_at, updated_at
                        ) values (
                            'UNDERWRITING_CONTRACT', ?, 1,
                            ?, 'ACTIVE', ?, ?, ?, ?, ?
                        )
                        """,
                        contractCode,
                        businessDate,
                        configJson,
                        changeReason,
                        changedBy,
                        now,
                        now
                ),
                "Underwriting contract policy version"
        );
    }

    private void verifyIssuedShareReconciliation(String symbol, long issuedShares) {
        Long holdingQuantity = jdbcClient.sql(
                        """
                        select coalesce(sum(quantity), 0)
                          from stock_holding
                         where symbol = ?
                        """
                )
                .param(symbol)
                .query(Long.class)
                .single();
        if (holdingQuantity == null || holdingQuantity != issuedShares) {
            throw new IllegalStateException(
                    "Underwriting allocation changed total issued-share holdings: "
                            + symbol
            );
        }
    }

    private String normalizedSymbol(String symbol) {
        String normalized = MarketTextNormalizer.symbol(symbol);
        if (normalized.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        return normalized;
    }

    private String normalizedUnderwritingType(UnderwritingContractCreateRequest request) {
        String normalized = request == null || request.underwritingType() == null
                ? "FIRM_COMMITMENT"
                : request.underwritingType().trim().toUpperCase(Locale.ROOT);
        if (!"FIRM_COMMITMENT".equals(normalized)) {
            throw StockException.badRequest(
                    "Independent initial issuance currently supports "
                            + "FIRM_COMMITMENT only"
            );
        }
        return normalized;
    }

    private String normalizeReason(UnderwritingContractCreateRequest request) {
        String reason = request == null ? null : request.changeReason();
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) {
            return "Create one underwriting account and contract for a pending issue";
        }
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String normalizeChangedBy(String changedBy) {
        String normalized = changedBy == null ? "" : changedBy.trim();
        if (normalized.isBlank()) {
            return "SYSTEM";
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private long insertWithGeneratedKey(
            String sql,
            PreparedStatementBinder binder
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );
            binder.bind(statement);
            return statement;
        }, keyHolder);
        requireSingleUpdate(inserted, "Underwriting generated-key insert");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Underwriting generated key is missing");
        }
        return key.longValue();
    }

    private void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(
                    operation + " count mismatch: expected=1, actual=" + count
            );
        }
    }

    @FunctionalInterface
    private interface PreparedStatementBinder {
        void bind(PreparedStatement statement) throws java.sql.SQLException;
    }

    private record PendingAllocation(
            String symbol,
            long issuedShares,
            long tradableShares,
            BigDecimal issuePrice,
            boolean marketEnabled,
            String marketStatus,
            long floatAllocationId,
            long corporateActionId,
            long floatCustodyAccountId,
            long floatQuantity,
            long floatReservedQuantity,
            BigDecimal floatAveragePrice,
            long lockedAllocationId
    ) {

        boolean pendingMarket() {
            return !marketEnabled && "CLOSED".equals(marketStatus);
        }

        boolean floatQuantityEligible() {
            return tradableShares > 0
                    && issuedShares > tradableShares
                    && floatReservedQuantity == 0
                    && floatQuantity >= tradableShares;
        }
    }

    private record MarketParticipant(
            long id,
            String participantType,
            String status,
            String selfTradeGroupId
    ) {
    }
}
