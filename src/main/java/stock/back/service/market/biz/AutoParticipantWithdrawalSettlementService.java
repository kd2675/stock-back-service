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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
        Map<String, UnderwriterTarget> discoveredTargets = findUnderwriterTargets(potentialSymbols);
        requireEverySymbolMapped(potentialSymbols, discoveredTargets);

        Set<Long> accountIdsToLock = new TreeSet<>();
        accountIdsToLock.add(discoveredAccount.getId());
        discoveredTargets.values().stream()
                .map(UnderwriterTarget::accountId)
                .forEach(accountIdsToLock::add);
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
            throw StockException.conflict("Auto-participant or listing-underwriter account changed; retry withdrawal");
        }

        StockAccount participantAccount = requireParticipantAccount(
                participantUserKey,
                discoveredAccount.getId(),
                lockedAccounts
        );
        requireUnderwriterAccounts(discoveredTargets.values(), lockedAccounts);
        requireNoPendingCorporateActionRights(participantAccount.getId());

        strategyTransitionService.retireAllOpenOrdersAndFundingBudgets(participantAccount, settledAt);

        List<HoldingReturn> holdings = lockParticipantHoldings(participantAccount.getId());
        requireBoundedSymbols(holdings.stream().map(HoldingReturn::symbol).toList());
        requireEverySymbolMapped(
                holdings.stream().map(HoldingReturn::symbol).toList(),
                discoveredTargets
        );
        requireNoReservedHoldings(holdings);

        List<CompletedShareReturn> completedReturns = new ArrayList<>(holdings.size());
        long returnedShareQuantity = 0L;
        for (HoldingReturn holding : holdings) {
            UnderwriterTarget target = discoveredTargets.get(holding.symbol());
            returnHoldingToUnderwriter(participantAccount.getId(), holding, target, settledAt);
            returnedShareQuantity = Math.addExact(returnedShareQuantity, holding.quantity());
            completedReturns.add(new CompletedShareReturn(
                    holding.symbol(),
                    target.accountId(),
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

    private Map<String, UnderwriterTarget> findUnderwriterTargets(List<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql(
                        """
                        select c.symbol,
                               a.id as account_id,
                               a.status,
                               a.participant_category
                          from stock_listing_auto_account_config c
                          join stock_account a on a.user_key = c.user_key
                         where c.symbol in (:symbols)
                         order by c.symbol asc
                        """
                )
                .param("symbols", symbols)
                .query((rs, rowNum) -> new UnderwriterTarget(
                        rs.getString("symbol"),
                        rs.getLong("account_id"),
                        rs.getString("status"),
                        rs.getString("participant_category")
                ))
                .list()
                .stream()
                .collect(Collectors.toMap(
                        UnderwriterTarget::symbol,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
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

    private void requireUnderwriterAccounts(
            Collection<UnderwriterTarget> targets,
            Map<Long, StockAccount> lockedAccounts
    ) {
        for (UnderwriterTarget target : targets) {
            StockAccount account = lockedAccounts.get(target.accountId());
            if (account == null
                    || account.getStatus() != StockAccountStatus.ACTIVE
                    || account.getParticipantCategory() != StockAccountParticipantCategory.LISTING_UNDERWRITER
                    || !StockAccountStatus.ACTIVE.name().equals(target.status())
                    || !StockAccountParticipantCategory.LISTING_UNDERWRITER.name().equals(target.participantCategory())) {
                throw StockException.conflict(
                        "Listing-underwriter account is not active for symbol: " + target.symbol()
                );
            }
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

    private void returnHoldingToUnderwriter(
            long participantAccountId,
            HoldingReturn holding,
            UnderwriterTarget target,
            LocalDateTime settledAt
    ) {
        Optional<UnderwriterHolding> underwriterHolding = jdbcClient.sql(
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
                .query((rs, rowNum) -> new UnderwriterHolding(
                        rs.getLong("quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getBigDecimal("average_price")
                ))
                .optional();
        if (underwriterHolding.isPresent()) {
            UnderwriterHolding current = underwriterHolding.get();
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
                throw new IllegalStateException("Listing-underwriter holding update failed: " + holding.symbol());
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
                throw new IllegalStateException("Listing-underwriter holding insert failed: " + holding.symbol());
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
                                withdrawal_id, symbol, underwriter_account_id,
                                quantity, source_average_price, created_at
                            )
                            values (
                                :withdrawalId, :symbol, :underwriterAccountId,
                                :quantity, :sourceAveragePrice, :createdAt
                            )
                            """
                    )
                    .param("withdrawalId", withdrawalId)
                    .param("symbol", completedReturn.symbol())
                    .param("underwriterAccountId", completedReturn.underwriterAccountId())
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

    private void requireEverySymbolMapped(
            List<String> symbols,
            Map<String, UnderwriterTarget> targets
    ) {
        List<String> missingSymbols = symbols.stream()
                .filter(symbol -> !targets.containsKey(symbol))
                .toList();
        if (!missingSymbols.isEmpty()) {
            throw StockException.conflict(
                    "Listing-underwriter account is missing for symbols: " + String.join(",", missingSymbols)
            );
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

    private record UnderwriterTarget(
            String symbol,
            long accountId,
            String status,
            String participantCategory
    ) {
    }

    private record HoldingReturn(
            String symbol,
            long quantity,
            long reservedQuantity,
            BigDecimal averagePrice
    ) {
    }

    private record UnderwriterHolding(
            long quantity,
            long reservedQuantity,
            BigDecimal averagePrice
    ) {
    }

    private record CompletedShareReturn(
            String symbol,
            long underwriterAccountId,
            long quantity,
            BigDecimal sourceAveragePrice
    ) {
    }
}
