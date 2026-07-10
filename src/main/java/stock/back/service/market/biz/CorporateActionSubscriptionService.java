package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlement;
import stock.back.service.database.entity.StockCorporateActionEntitlementStatus;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.market.vo.CorporateActionEntitlementResponse;
import stock.back.service.market.vo.CorporateActionSubscriptionRequest;
import web.common.core.simulation.SimulationMarketSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CorporateActionSubscriptionService {

    private static final String SUBSCRIBED = StockCorporateActionEntitlementStatus.SUBSCRIBED.name();
    private static final String PAID = StockCorporateActionEntitlementStatus.PAID.name();

    private final StockCorporateActionRepository stockCorporateActionRepository;
    private final StockCorporateActionEntitlementRepository stockCorporateActionEntitlementRepository;
    private final StockAccountRepository stockAccountRepository;
    private final StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final JdbcClient jdbcClient;

    @Transactional
    public CorporateActionEntitlementResponse subscribe(long actionId, CorporateActionSubscriptionRequest request, String userKey) {
        long requestedShares = requirePositiveShares(request == null ? null : request.shareQuantity());
        validateAfterCloseSubscriptionSession();
        StockCorporateAction action = stockCorporateActionRepository.findByIdForUpdate(actionId)
                .orElseThrow(() -> StockException.notFound("Corporate action not found: " + actionId));
        validateSubscribableAction(action);
        validateSubscriptionWindow(action, simulationClockService.currentDate());

        StockAccount account = stockAccountRepository.findByUserKeyAndStatusForUpdate(userKey, StockAccountStatus.ACTIVE)
                .orElseThrow(() -> StockException.notFound("User account is not opened yet"));
        Optional<StockCorporateActionEntitlement> existingEntitlement =
                stockCorporateActionEntitlementRepository.findByActionIdAndAccountId(action.getId(), account.getId());
        validateExistingEntitlement(action, existingEntitlement);

        LocalDateTime now = currentDateTime();
        BigDecimal subscribedCashAmount = action.getIssuePrice().multiply(BigDecimal.valueOf(requestedShares));
        if (!account.withdrawCash(subscribedCashAmount, now)) {
            throw StockException.badRequest("Insufficient cash balance for capital increase subscription");
        }
        stockAccountCashFlowRepository.save(StockAccountCashFlow.capitalIncreaseSubscription(
                account.getId(),
                subscribedCashAmount,
                now
        ));

        StockCorporateActionEntitlement entitlement = action.getOfferingType() == StockCapitalIncreaseOfferingType.PUBLIC_OFFERING
                ? createPublicOfferingSubscription(action, account.getId(), requestedShares, subscribedCashAmount)
                : subscribeShareholderAllocation(action, account.getId(), requestedShares, subscribedCashAmount);
        return toResponse(entitlement, action);
    }

    private long requirePositiveShares(Long shareQuantity) {
        if (shareQuantity == null || shareQuantity <= 0) {
            throw StockException.badRequest("Subscription share quantity must be positive");
        }
        return shareQuantity;
    }

    private void validateAfterCloseSubscriptionSession() {
        if (simulationMarketSessionService.currentSession() != SimulationMarketSession.AFTER_CLOSE) {
            throw StockException.conflict("Capital increase subscription is only available after market close");
        }
    }

    private void validateSubscribableAction(StockCorporateAction action) {
        if (action.getActionType() != StockCorporateActionType.PAID_IN_CAPITAL_INCREASE) {
            throw StockException.badRequest("Only paid-in capital increase can be subscribed");
        }
        if (action.getIssuePrice() == null || action.getIssuePrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.conflict("Capital increase issue price is missing");
        }
        if (action.getShareQuantity() == null || action.getShareQuantity() <= 0) {
            throw StockException.conflict("Capital increase offering shares are missing");
        }
        if (action.getOfferingType() == null) {
            throw StockException.conflict("Capital increase offering type is missing");
        }
        if (action.getStatus() == StockCorporateActionStatus.PAID || action.getStatus() == StockCorporateActionStatus.LISTED) {
            throw StockException.conflict("Capital increase subscription period is already closed");
        }
        if (action.getOfferingType() == StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION
                && action.getStatus() != StockCorporateActionStatus.EX_RIGHTS_APPLIED) {
            throw StockException.conflict("Shareholder allocation rights are not granted yet");
        }
        if (action.getOfferingType() == StockCapitalIncreaseOfferingType.PUBLIC_OFFERING
                && action.getStatus() != StockCorporateActionStatus.ANNOUNCED) {
            throw StockException.conflict("Public offering is not subscribable");
        }
    }

    private void validateSubscriptionWindow(StockCorporateAction action, LocalDate currentDate) {
        LocalDate startDate = action.getSubscriptionStartDate();
        LocalDate endDate = action.getSubscriptionEndDate();
        if (startDate == null || endDate == null) {
            throw StockException.conflict("Capital increase subscription schedule is missing");
        }
        if (currentDate.isBefore(startDate)) {
            throw StockException.conflict("Capital increase subscription has not started yet");
        }
        if (currentDate.isAfter(endDate)) {
            throw StockException.conflict("Capital increase subscription is already closed");
        }
    }

    private void validateExistingEntitlement(
            StockCorporateAction action,
            Optional<StockCorporateActionEntitlement> existingEntitlement
    ) {
        if (action.getOfferingType() == StockCapitalIncreaseOfferingType.PUBLIC_OFFERING
                && existingEntitlement.isPresent()) {
            throw StockException.conflict("Capital increase subscription already exists");
        }
        if (action.getOfferingType() == StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION
                && existingEntitlement
                .filter(entitlement -> entitlement.getStatus() != StockCorporateActionEntitlementStatus.ANNOUNCED)
                .isPresent()) {
            throw StockException.conflict("Shareholder allocation right is not subscribable");
        }
    }

    private StockCorporateActionEntitlement createPublicOfferingSubscription(
            StockCorporateAction action,
            long accountId,
            long requestedShares,
            BigDecimal subscribedCashAmount
    ) {
        long remainingShares = remainingOfferingShares(action);
        if (requestedShares > remainingShares) {
            throw StockException.conflict("Capital increase public offering shares are insufficient");
        }
        LocalDateTime now = currentDateTime();
        jdbcClient.sql(
                        """
                        insert into stock_corporate_action_entitlement(
                          action_id, account_id, symbol, quantity, share_quantity, cash_amount,
                          subscribed_share_quantity, subscribed_cash_amount, status,
                          holding_snapshot_run_id, created_at, subscribed_at, paid_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, null)
                        """
                )
                .param(action.getId())
                .param(accountId)
                .param(action.getSymbol())
                .param(requestedShares)
                .param(requestedShares)
                .param(subscribedCashAmount)
                .param(requestedShares)
                .param(subscribedCashAmount)
                .param(SUBSCRIBED)
                .param(now)
                .param(now)
                .update();
        return stockCorporateActionEntitlementRepository.findByActionIdAndAccountId(action.getId(), accountId)
                .orElseThrow(() -> StockException.conflict("Capital increase subscription was not recorded"));
    }

    private StockCorporateActionEntitlement subscribeShareholderAllocation(
            StockCorporateAction action,
            long accountId,
            long requestedShares,
            BigDecimal subscribedCashAmount
    ) {
        StockCorporateActionEntitlement entitlement = stockCorporateActionEntitlementRepository
                .findByActionIdAndAccountIdForUpdate(action.getId(), accountId)
                .orElseThrow(() -> StockException.conflict("Shareholder allocation right was not granted"));
        if (entitlement.getStatus() != StockCorporateActionEntitlementStatus.ANNOUNCED) {
            throw StockException.conflict("Shareholder allocation right is not subscribable");
        }
        long availableShares = entitlement.getShareQuantity() == null ? 0L : entitlement.getShareQuantity();
        if (requestedShares > availableShares) {
            throw StockException.badRequest("Subscription share quantity exceeds allocated shareholder rights");
        }
        LocalDateTime now = currentDateTime();
        int updated = jdbcClient.sql(
                        """
                        update stock_corporate_action_entitlement
                           set subscribed_share_quantity = ?,
                               subscribed_cash_amount = ?,
                               status = ?,
                               subscribed_at = ?
                         where id = ?
                           and status = 'ANNOUNCED'
                        """
                )
                .param(requestedShares)
                .param(subscribedCashAmount)
                .param(SUBSCRIBED)
                .param(now)
                .param(entitlement.getId())
                .update();
        if (updated == 0) {
            throw StockException.conflict("Shareholder allocation right is not subscribable");
        }
        return stockCorporateActionEntitlementRepository.findById(entitlement.getId())
                .orElseThrow(() -> StockException.conflict("Capital increase subscription was not recorded"));
    }

    private long remainingOfferingShares(StockCorporateAction action) {
        long subscribedShares = jdbcClient.sql(
                        """
                        select coalesce(sum(subscribed_share_quantity), 0)
                          from stock_corporate_action_entitlement
                         where action_id = ?
                           and status in (?, ?)
                        """
                )
                .param(action.getId())
                .param(SUBSCRIBED)
                .param(PAID)
                .query(Long.class)
                .single();
        return Math.max(0, action.getShareQuantity() - subscribedShares);
    }

    private LocalDateTime currentDateTime() {
        return simulationClockService.currentMarketDateTime();
    }

    private CorporateActionEntitlementResponse toResponse(
            StockCorporateActionEntitlement entitlement,
            StockCorporateAction action
    ) {
        return new CorporateActionEntitlementResponse(
                entitlement.getId(),
                entitlement.getAccountId(),
                entitlement.getActionId(),
                entitlement.getSymbol(),
                action.getActionType(),
                entitlement.getQuantity() == null ? 0L : entitlement.getQuantity(),
                entitlement.getShareQuantity(),
                entitlement.getCashAmount(),
                entitlement.getSubscribedShareQuantity(),
                entitlement.getSubscribedCashAmount(),
                entitlement.getStatus(),
                entitlement.getCreatedAt(),
                entitlement.getSubscribedAt(),
                entitlement.getPaidAt()
        );
    }
}
