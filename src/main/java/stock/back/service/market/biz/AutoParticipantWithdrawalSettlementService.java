package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountParticipantCategory;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class AutoParticipantWithdrawalSettlementService {

    private static final int MAX_RETURN_SYMBOL_COUNT = 500;
    private static final String SYSTEM_WITHDRAWAL_ACTOR = "AUTO_PARTICIPANT_WITHDRAWAL";
    private static final String SYSTEM_CUSTODY_USER_KEY = "stock-system-custody";
    private static final String SYSTEM_CUSTODY_SELF_TRADE_GROUP = "SYSTEM_CUSTODY:DEFAULT";
    private static final String WITHDRAWAL_CUSTODY_TRANSFER_REASON =
            "AUTO_PARTICIPANT_WITHDRAWAL_CUSTODY";

    private final StockAccountRepository stockAccountRepository;
    private final StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private final AutoParticipantStrategyTransitionService strategyTransitionService;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    AutoParticipantWithdrawalSettlementService(
            StockAccountRepository stockAccountRepository,
            StockAccountCashFlowRepository stockAccountCashFlowRepository,
            AutoParticipantStrategyTransitionService strategyTransitionService,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate
    ) {
        this.stockAccountRepository = stockAccountRepository;
        this.stockAccountCashFlowRepository = stockAccountCashFlowRepository;
        this.strategyTransitionService = strategyTransitionService;
        this.jdbcTemplate = namedParameterJdbcTemplate.getJdbcTemplate();
        this.jdbcClient = JdbcClient.create(namedParameterJdbcTemplate);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    WithdrawalSettlement settle(String participantUserKey, String adminUserKey, LocalDateTime settledAt) {
        if (settledAt == null) {
            throw new IllegalArgumentException("Auto-participant withdrawal time is required");
        }
        StockAccount discoveredAccount = stockAccountRepository.findByUserKey(participantUserKey).orElse(null);
        if (discoveredAccount == null) {
            return WithdrawalSettlement.noAccount();
        }
        if (discoveredAccount.getId() == null) {
            throw new IllegalStateException("Auto-participant account id is missing: " + participantUserKey);
        }

        List<String> potentialSymbols = findPotentialReturnSymbols(discoveredAccount.getId());
        requireBoundedSymbols(potentialSymbols);
        CustodyTarget discoveredTarget = potentialSymbols.isEmpty()
                ? null
                : findSystemCustodyTarget();

        Set<Long> accountIdsToLock = new TreeSet<>();
        accountIdsToLock.add(discoveredAccount.getId());
        if (discoveredTarget != null) {
            accountIdsToLock.add(discoveredTarget.accountId());
        }
        Map<Long, StockAccount> lockedAccounts = stockAccountRepository
                .findAllByIdInForUpdate(accountIdsToLock)
                .stream()
                .collect(Collectors.toMap(
                        StockAccount::getId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
        ));
        if (lockedAccounts.size() != accountIdsToLock.size()) {
            throw StockException.conflict("Auto-participant or system-custody account changed; retry withdrawal");
        }

        StockAccount participantAccount = requireParticipantAccount(
                participantUserKey,
                discoveredAccount.getId(),
                lockedAccounts
        );
        if (discoveredTarget != null) {
            requireSystemCustodyAccount(
                    discoveredTarget,
                    lockedAccounts,
                    settledAt.toLocalDate()
            );
        }
        requireNoPendingCorporateActionRights(participantAccount.getId());

        strategyTransitionService.retireAllOpenOrdersAndFundingBudgets(participantAccount, settledAt);

        List<HoldingReturn> holdings = lockParticipantHoldings(participantAccount.getId());
        requireBoundedSymbols(holdings.stream().map(HoldingReturn::symbol).toList());
        if (!holdings.isEmpty() && discoveredTarget == null) {
            throw StockException.conflict("System-custody account is required for withdrawal share transfer");
        }
        requireNoReservedHoldings(holdings);

        List<CompletedShareReturn> completedReturns = new ArrayList<>(holdings.size());
        long returnedShareQuantity = 0L;
        for (HoldingReturn holding : holdings) {
            returnHoldingToSystemCustody(
                    participantAccount.getId(),
                    holding,
                    discoveredTarget,
                    settledAt
            );
            returnedShareQuantity = Math.addExact(returnedShareQuantity, holding.quantity());
            completedReturns.add(new CompletedShareReturn(
                    holding.symbol(),
                    discoveredTarget.accountId(),
                    StockAccountParticipantCategory.SYSTEM_CUSTODY.name(),
                    WITHDRAWAL_CUSTODY_TRANSFER_REASON,
                    holding.quantity(),
                    holding.averagePrice()
            ));
        }

        BigDecimal returnedCashAmount = zeroIfNull(participantAccount.getCashBalance());
        String withdrawalActor = normalizedActor(adminUserKey);
        if (returnedCashAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (!participantAccount.withdrawCash(returnedCashAmount, settledAt)) {
                throw new IllegalStateException("Auto-participant cash changed while settling withdrawal");
            }
            stockAccountCashFlowRepository.save(StockAccountCashFlow.adminWithdraw(
                    participantAccount.getId(),
                    returnedCashAmount,
                    withdrawalActor,
                    settledAt
            ));
        }
        participantAccount.closeForAutoParticipantWithdrawal(settledAt);

        long withdrawalId = insertWithdrawalAudit(
                participantUserKey,
                participantAccount.getId(),
                returnedCashAmount,
                returnedShareQuantity,
                completedReturns.size(),
                withdrawalActor,
                settledAt
        );
        insertShareReturnAudits(withdrawalId, completedReturns, settledAt);
        return new WithdrawalSettlement(
                returnedCashAmount,
                returnedShareQuantity,
                completedReturns.size(),
                true
        );
    }

    Optional<WithdrawalSettlement> findCompletedSettlement(String participantUserKey) {
        return jdbcClient.sql(
                        """
                        select returned_cash_amount,
                               returned_share_quantity,
                               returned_symbol_count
                          from stock_auto_participant_withdrawal
                         where participant_user_key = :participantUserKey
                        """
                )
                .param("participantUserKey", participantUserKey)
                .query((rs, rowNum) -> new WithdrawalSettlement(
                        zeroIfNull(rs.getBigDecimal("returned_cash_amount")),
                        rs.getLong("returned_share_quantity"),
                        rs.getInt("returned_symbol_count"),
                        true
                ))
                .optional();
    }

    private List<String> findPotentialReturnSymbols(long accountId) {
        return jdbcClient.sql(
                        """
                        select symbol
                          from stock_holding
                         where account_id = :holdingAccountId
                           and (quantity > 0 or reserved_quantity > 0)
                         order by symbol asc
                        """
                )
                .param("holdingAccountId", accountId)
                .query(String.class)
                .list();
    }

    private CustodyTarget findSystemCustodyTarget() {
        return jdbcClient.sql(
                        """
                        select account.id as account_id,
                               account.status as account_status,
                               account.participant_category,
                               account.self_trade_group_id,
                               participant.participant_type,
                               participant.status as participant_status,
                               participant_account.account_role,
                               participant_account.status as participant_account_status,
                               participant_account.effective_from,
                               participant_account.effective_to
                          from stock_account account
                          join stock_market_participant_account participant_account
                            on participant_account.account_id = account.id
                          join stock_market_participant participant
                            on participant.id = participant_account.participant_id
                         where account.user_key = :userKey
                           and participant.participant_code = 'SYSTEM_CUSTODY'
                        """
                )
                .param("userKey", SYSTEM_CUSTODY_USER_KEY)
                .query((rs, rowNum) -> new CustodyTarget(
                        rs.getLong("account_id"),
                        rs.getString("account_status"),
                        rs.getString("participant_category"),
                        rs.getString("self_trade_group_id"),
                        rs.getString("participant_type"),
                        rs.getString("participant_status"),
                        rs.getString("account_role"),
                        rs.getString("participant_account_status"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class)
                ))
                .optional()
                .orElseThrow(() -> StockException.conflict(
                        "Active SYSTEM_CUSTODY account is not provisioned"
                ));
    }

    private StockAccount requireParticipantAccount(
            String participantUserKey,
            long participantAccountId,
            Map<Long, StockAccount> lockedAccounts
    ) {
        StockAccount account = lockedAccounts.get(participantAccountId);
        if (account == null
                || !participantUserKey.equals(account.getUserKey())
                || account.getStatus() != StockAccountStatus.ACTIVE
                || account.getParticipantCategory() != StockAccountParticipantCategory.AUTO_PARTICIPANT) {
            throw StockException.conflict("Auto-participant account is not active: " + participantUserKey);
        }
        return account;
    }

    private void requireSystemCustodyAccount(
            CustodyTarget target,
            Map<Long, StockAccount> lockedAccounts,
            LocalDate settlementDate
    ) {
        StockAccount account = lockedAccounts.get(target.accountId());
        if (account == null
                || account.getStatus() != StockAccountStatus.ACTIVE
                || account.getParticipantCategory() != StockAccountParticipantCategory.SYSTEM_CUSTODY
                || !SYSTEM_CUSTODY_SELF_TRADE_GROUP.equals(account.getSelfTradeGroupId())
                || !StockAccountStatus.ACTIVE.name().equals(target.accountStatus())
                || !StockAccountParticipantCategory.SYSTEM_CUSTODY.name().equals(target.participantCategory())
                || !SYSTEM_CUSTODY_SELF_TRADE_GROUP.equals(target.selfTradeGroupId())
                || !StockAccountParticipantCategory.SYSTEM_CUSTODY.name().equals(target.participantType())
                || !"ACTIVE".equals(target.participantStatus())
                || !StockAccountParticipantCategory.SYSTEM_CUSTODY.name().equals(target.accountRole())
                || !"ACTIVE".equals(target.participantAccountStatus())
                || target.effectiveFrom() == null
                || settlementDate.isBefore(target.effectiveFrom())
                || (target.effectiveTo() != null && settlementDate.isAfter(target.effectiveTo()))) {
            throw StockException.conflict("SYSTEM_CUSTODY account mapping is not active or consistent");
        }
        requireCustodyNonTradingState(target.accountId());
    }

    private void requireCustodyNonTradingState(long custodyAccountId) {
        Long openOrderCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_order
                         where account_id = :accountId
                           and status in ('PENDING', 'PARTIALLY_FILLED')
                           and quantity > filled_quantity
                        """
                )
                .param("accountId", custodyAccountId)
                .query(Long.class)
                .single();
        Long reservedHoldingCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_holding
                         where account_id = :accountId
                           and reserved_quantity > 0
                        """
                )
                .param("accountId", custodyAccountId)
                .query(Long.class)
                .single();
        if ((openOrderCount != null && openOrderCount > 0L)
                || (reservedHoldingCount != null && reservedHoldingCount > 0L)) {
            throw StockException.conflict(
                    "SYSTEM_CUSTODY must not contain open orders or reserved holdings"
            );
        }
    }

    private void requireNoPendingCorporateActionRights(long accountId) {
        Long pendingEntitlementCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_corporate_action_entitlement
                         where account_id = :accountId
                           and status in ('ANNOUNCED', 'PARTIALLY_SUBSCRIBED', 'SUBSCRIBED')
                        """
                )
                .param("accountId", accountId)
                .query(Long.class)
                .single();
        Long frozenRightCount = jdbcClient.sql(
                        """
                        select count(distinct action.id)
                          from stock_corporate_action action
                         where action.entitlement_close_run_id is not null
                           and not (
                               (action.action_type = 'CASH_DIVIDEND' and action.status = 'PAID')
                               or (action.action_type in (
                                   'INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE',
                                   'STOCK_SPLIT', 'BONUS_ISSUE', 'STOCK_DIVIDEND'
                               ) and action.status = 'LISTED')
                               or (action.action_type = 'DELISTING' and action.status = 'DELISTED')
                           )
                           and exists (
                               select 1
                                 from stock_holding_snapshot snapshot
                                where snapshot.close_run_id = action.entitlement_close_run_id
                                  and snapshot.account_id = :snapshotAccountId
                                  and snapshot.symbol = action.symbol
                                  and snapshot.quantity > 0
                           )
                        """
                )
                .param("snapshotAccountId", accountId)
                .query(Long.class)
                .single();
        long blockerCount = (pendingEntitlementCount == null ? 0L : pendingEntitlementCount)
                + (frozenRightCount == null ? 0L : frozenRightCount);
        if (blockerCount > 0) {
            throw StockException.conflict(
                    "Auto participant has pending corporate-action rights; complete payment or listing before withdrawal"
            );
        }
    }

    private List<HoldingReturn> lockParticipantHoldings(long accountId) {
        return jdbcClient.sql(
                        """
                        select symbol, quantity, reserved_quantity, average_price
                          from stock_holding
                         where account_id = :accountId
                           and (quantity > 0 or reserved_quantity > 0)
                         order by symbol asc
                         for update
                        """
                )
                .param("accountId", accountId)
                .query((rs, rowNum) -> new HoldingReturn(
                        rs.getString("symbol"),
                        rs.getLong("quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getBigDecimal("average_price")
                ))
                .list();
    }

    private void requireNoReservedHoldings(List<HoldingReturn> holdings) {
        List<String> reservedSymbols = holdings.stream()
                .filter(holding -> holding.reservedQuantity() > 0)
                .map(HoldingReturn::symbol)
                .toList();
        if (!reservedSymbols.isEmpty()) {
            throw StockException.conflict(
                    "Reserved holdings remain after order cleanup: " + String.join(",", reservedSymbols)
            );
        }
    }

    private void returnHoldingToSystemCustody(
            long participantAccountId,
            HoldingReturn holding,
            CustodyTarget target,
            LocalDateTime settledAt
    ) {
        Optional<ReceiverHolding> receiverHolding = jdbcClient.sql(
                        """
                        select quantity, reserved_quantity, average_price
                          from stock_holding
                         where account_id = :accountId
                           and symbol = :symbol
                         for update
                        """
                )
                .param("accountId", target.accountId())
                .param("symbol", holding.symbol())
                .query((rs, rowNum) -> new ReceiverHolding(
                        rs.getLong("quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getBigDecimal("average_price")
                ))
                .optional();
        if (receiverHolding.isPresent()) {
            ReceiverHolding current = receiverHolding.get();
            long nextQuantity = Math.addExact(current.quantity(), holding.quantity());
            BigDecimal nextAveragePrice = weightedAveragePrice(
                    current.quantity(),
                    current.averagePrice(),
                    holding.quantity(),
                    holding.averagePrice()
            );
            int updated = jdbcClient.sql(
                            """
                            update stock_holding
                               set quantity = :quantity,
                                   average_price = :averagePrice,
                                   updated_at = :updatedAt
                             where account_id = :accountId
                               and symbol = :symbol
                            """
                    )
                    .param("quantity", nextQuantity)
                    .param("averagePrice", nextAveragePrice)
                    .param("updatedAt", settledAt)
                    .param("accountId", target.accountId())
                    .param("symbol", holding.symbol())
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("System-custody holding update failed: " + holding.symbol());
            }
        } else {
            int inserted = jdbcClient.sql(
                            """
                            insert into stock_holding(
                                account_id, symbol, quantity, reserved_quantity, average_price, updated_at
                            )
                            values (:accountId, :symbol, :quantity, 0, :averagePrice, :updatedAt)
                            """
                    )
                    .param("accountId", target.accountId())
                    .param("symbol", holding.symbol())
                    .param("quantity", holding.quantity())
                    .param("averagePrice", holding.averagePrice())
                    .param("updatedAt", settledAt)
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException("System-custody holding insert failed: " + holding.symbol());
            }
        }

        int cleared = jdbcClient.sql(
                        """
                        update stock_holding
                           set quantity = 0,
                               reserved_quantity = 0,
                               updated_at = :updatedAt
                         where account_id = :accountId
                           and symbol = :symbol
                        """
                )
                .param("updatedAt", settledAt)
                .param("accountId", participantAccountId)
                .param("symbol", holding.symbol())
                .update();
        if (cleared != 1) {
            throw new IllegalStateException("Auto-participant holding cleanup failed: " + holding.symbol());
        }
    }

    private BigDecimal weightedAveragePrice(
            long currentQuantity,
            BigDecimal currentAveragePrice,
            long returnedQuantity,
            BigDecimal returnedAveragePrice
    ) {
        long totalQuantity = Math.addExact(currentQuantity, returnedQuantity);
        if (totalQuantity <= 0) {
            throw new IllegalArgumentException("Combined holding quantity must be positive");
        }
        BigDecimal currentCost = zeroIfNull(currentAveragePrice).multiply(BigDecimal.valueOf(currentQuantity));
        BigDecimal returnedCost = zeroIfNull(returnedAveragePrice).multiply(BigDecimal.valueOf(returnedQuantity));
        return currentCost.add(returnedCost)
                .divide(BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP);
    }

    private long insertWithdrawalAudit(
            String participantUserKey,
            long accountId,
            BigDecimal returnedCashAmount,
            long returnedShareQuantity,
            int returnedSymbolCount,
            String createdBy,
            LocalDateTime createdAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator statementCreator = connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    insert into stock_auto_participant_withdrawal(
                        participant_user_key, account_id, returned_cash_amount,
                        returned_share_quantity, returned_symbol_count, created_by, created_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, participantUserKey);
            statement.setLong(2, accountId);
            statement.setBigDecimal(3, returnedCashAmount);
            statement.setLong(4, returnedShareQuantity);
            statement.setInt(5, returnedSymbolCount);
            statement.setString(6, createdBy);
            statement.setObject(7, createdAt);
            return statement;
        };
        int inserted = jdbcTemplate.update(statementCreator, keyHolder);
        Number generatedKey = keyHolder.getKey();
        if (inserted != 1 || generatedKey == null) {
            throw new IllegalStateException("Auto-participant withdrawal audit insert failed");
        }
        return generatedKey.longValue();
    }

    private void insertShareReturnAudits(
            long withdrawalId,
            List<CompletedShareReturn> completedReturns,
            LocalDateTime createdAt
    ) {
        for (CompletedShareReturn completedReturn : completedReturns) {
            int inserted = jdbcClient.sql(
                            """
                            insert into stock_auto_participant_share_return(
                                withdrawal_id, symbol, receiver_account_id,
                                receiver_role, transfer_reason,
                                quantity, source_average_price, created_at
                            )
                            values (
                                :withdrawalId, :symbol, :receiverAccountId,
                                :receiverRole, :transferReason,
                                :quantity, :sourceAveragePrice, :createdAt
                            )
                            """
                    )
                    .param("withdrawalId", withdrawalId)
                    .param("symbol", completedReturn.symbol())
                    .param("receiverAccountId", completedReturn.receiverAccountId())
                    .param("receiverRole", completedReturn.receiverRole())
                    .param("transferReason", completedReturn.transferReason())
                    .param("quantity", completedReturn.quantity())
                    .param("sourceAveragePrice", completedReturn.sourceAveragePrice())
                    .param("createdAt", createdAt)
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Auto-participant share-return audit insert failed: " + completedReturn.symbol()
                );
            }
        }
    }

    private void requireBoundedSymbols(List<String> symbols) {
        if (symbols.size() > MAX_RETURN_SYMBOL_COUNT) {
            throw StockException.conflict(
                    "Auto-participant holds too many symbols for synchronous withdrawal: " + symbols.size()
            );
        }
    }

    private String normalizedActor(String adminUserKey) {
        if (adminUserKey == null || adminUserKey.isBlank()) {
            return SYSTEM_WITHDRAWAL_ACTOR;
        }
        String normalized = adminUserKey.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    record WithdrawalSettlement(
            BigDecimal returnedCashAmount,
            long returnedShareQuantity,
            int returnedSymbolCount,
            boolean accountClosed
    ) {
        static WithdrawalSettlement noAccount() {
            return new WithdrawalSettlement(BigDecimal.ZERO, 0L, 0, false);
        }
    }

    private record CustodyTarget(
            long accountId,
            String accountStatus,
            String participantCategory,
            String selfTradeGroupId,
            String participantType,
            String participantStatus,
            String accountRole,
            String participantAccountStatus,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
    }

    private record HoldingReturn(
            String symbol,
            long quantity,
            long reservedQuantity,
            BigDecimal averagePrice
    ) {
    }

    private record ReceiverHolding(
            long quantity,
            long reservedQuantity,
            BigDecimal averagePrice
    ) {
    }

    private record CompletedShareReturn(
            String symbol,
            long receiverAccountId,
            String receiverRole,
            String transferReason,
            long quantity,
            BigDecimal sourceAveragePrice
    ) {
    }
}
