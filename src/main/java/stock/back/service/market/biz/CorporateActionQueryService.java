package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlement;
import stock.back.service.database.entity.StockCorporateActionEntitlementStatus;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.CorporateActionEntitlementResponse;
import stock.back.service.market.vo.CorporateActionResponse;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CorporateActionQueryService {

    private static final int DEFAULT_FEED_LIMIT = 100;
    private static final int MAX_FEED_LIMIT = 200;
    private static final List<StockCorporateActionStatus> ACTIVE_PAID_IN_STATUSES = List.of(
            StockCorporateActionStatus.ANNOUNCED,
            StockCorporateActionStatus.EX_RIGHTS_APPLIED,
            StockCorporateActionStatus.PAID
    );
    private static final Comparator<StockCorporateAction> FEED_ORDER = Comparator
            .comparing(StockCorporateAction::getCreatedAt, Comparator.reverseOrder())
            .thenComparing(StockCorporateAction::getId, Comparator.reverseOrder());

    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockCorporateActionRepository stockCorporateActionRepository;
    private final StockAccountRepository stockAccountRepository;
    private final StockCorporateActionEntitlementRepository stockCorporateActionEntitlementRepository;

    @Transactional(readOnly = true)
    public List<CorporateActionResponse> getCorporateActions(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        List<StockCorporateAction> actions = stockCorporateActionRepository.findBySymbolOrderByCreatedAtDesc(normalizedSymbol);
        return toCorporateActionResponses(actions);
    }

    @Transactional(readOnly = true)
    public List<CorporateActionResponse> getCorporateActions(StockCorporateActionType actionType, Integer limit) {
        int normalizedLimit = normalizeFeedLimit(limit);
        Pageable pageable = PageRequest.of(0, normalizedLimit);
        List<StockCorporateAction> recentActions = actionType == null
                ? stockCorporateActionRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : stockCorporateActionRepository.findByActionTypeOrderByCreatedAtDescIdDesc(actionType, pageable);
        List<StockCorporateAction> actions = includeActivePaidInActions(actionType, recentActions);
        return toCorporateActionResponses(actions);
    }

    private List<StockCorporateAction> includeActivePaidInActions(
            StockCorporateActionType actionType,
            List<StockCorporateAction> recentActions
    ) {
        if (actionType != null && actionType != StockCorporateActionType.PAID_IN_CAPITAL_INCREASE) {
            return recentActions;
        }
        List<StockCorporateAction> activePaidInActions = stockCorporateActionRepository
                .findByActionTypeAndStatusInOrderByCreatedAtDescIdDesc(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        ACTIVE_PAID_IN_STATUSES
                );
        Map<Long, StockCorporateAction> uniqueActions = new LinkedHashMap<>();
        recentActions.forEach(action -> uniqueActions.put(action.getId(), action));
        activePaidInActions.forEach(action -> uniqueActions.putIfAbsent(action.getId(), action));
        return uniqueActions.values().stream()
                .sorted(FEED_ORDER)
                .toList();
    }

    private List<CorporateActionResponse> toCorporateActionResponses(List<StockCorporateAction> actions) {
        Map<Long, Long> subscribedShareQuantities = subscribedShareQuantities(actions);
        return actions.stream()
                .map(action -> toCorporateActionResponse(action, subscribedShareQuantities.getOrDefault(action.getId(), 0L)))
                .toList();
    }

    private int normalizeFeedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_FEED_LIMIT;
        }
        if (limit <= 0 || limit > MAX_FEED_LIMIT) {
            throw StockException.badRequest("Corporate action feed limit must be between 1 and " + MAX_FEED_LIMIT);
        }
        return limit;
    }

    @Transactional(readOnly = true)
    public List<CorporateActionEntitlementResponse> getMyCorporateActionEntitlements(String userKey) {
        Long accountId = stockAccountRepository.findByUserKeyAndStatus(userKey, StockAccountStatus.ACTIVE)
                .map(stock.back.service.database.entity.StockAccount::getId)
                .orElse(null);
        if (accountId == null) {
            return List.of();
        }
        List<StockCorporateActionEntitlement> entitlements = userEntitlements(accountId);
        Map<Long, StockCorporateAction> actionsById = stockCorporateActionRepository.findAllById(
                        entitlements.stream()
                                .map(StockCorporateActionEntitlement::getActionId)
                                .toList()
                ).stream()
                .collect(Collectors.toMap(StockCorporateAction::getId, Function.identity()));
        return entitlements.stream()
                .map(entitlement -> toCorporateActionEntitlementResponse(entitlement, actionsById.get(entitlement.getActionId())))
                .toList();
    }

    private List<StockCorporateActionEntitlement> userEntitlements(Long accountId) {
        List<StockCorporateActionEntitlement> activeEntitlements = stockCorporateActionEntitlementRepository
                .findByAccountIdAndStatusInOrderByCreatedAtDesc(
                        accountId,
                        List.of(
                                StockCorporateActionEntitlementStatus.ANNOUNCED,
                                StockCorporateActionEntitlementStatus.PARTIALLY_SUBSCRIBED,
                                StockCorporateActionEntitlementStatus.SUBSCRIBED
                        )
                );
        List<StockCorporateActionEntitlement> recentEntitlements =
                stockCorporateActionEntitlementRepository.findTop50ByAccountIdOrderByCreatedAtDesc(accountId);
        Map<Long, StockCorporateActionEntitlement> uniqueEntitlements = new LinkedHashMap<>();
        activeEntitlements.forEach(entitlement -> uniqueEntitlements.put(entitlement.getId(), entitlement));
        recentEntitlements.forEach(entitlement -> uniqueEntitlements.putIfAbsent(entitlement.getId(), entitlement));
        return List.copyOf(uniqueEntitlements.values());
    }

    private Map<Long, Long> subscribedShareQuantities(List<StockCorporateAction> actions) {
        List<Long> actionIds = actions.stream()
                .filter(action -> action.getActionType() == StockCorporateActionType.PAID_IN_CAPITAL_INCREASE)
                .map(StockCorporateAction::getId)
                .filter(id -> id != null)
                .toList();
        if (actionIds.isEmpty()) {
            return Map.of();
        }
        return stockCorporateActionEntitlementRepository.sumSubscribedShareQuantityByActionIdInAndStatusIn(
                        actionIds,
                        List.of(
                                StockCorporateActionEntitlementStatus.PARTIALLY_SUBSCRIBED,
                                StockCorporateActionEntitlementStatus.SUBSCRIBED,
                                StockCorporateActionEntitlementStatus.PAID
                        )
                ).stream()
                .collect(Collectors.toMap(
                        StockCorporateActionEntitlementRepository.SubscribedShareQuantitySummary::getActionId,
                        summary -> summary.getSubscribedShareQuantity() == null ? 0L : summary.getSubscribedShareQuantity()
                ));
    }

    private CorporateActionResponse toCorporateActionResponse(StockCorporateAction action, Long subscribedShareQuantity) {
        Long normalizedSubscribedShareQuantity = null;
        Long remainingShareQuantity = null;
        if (action.getActionType() == StockCorporateActionType.PAID_IN_CAPITAL_INCREASE) {
            normalizedSubscribedShareQuantity = Math.max(0L, subscribedShareQuantity == null ? 0L : subscribedShareQuantity);
            remainingShareQuantity = action.getShareQuantity() == null
                    ? null
                    : Math.max(0L, action.getShareQuantity() - normalizedSubscribedShareQuantity);
        }
        return new CorporateActionResponse(
                action.getId(),
                action.getSymbol(),
                action.getActionType(),
                action.getShareQuantity(),
                normalizedSubscribedShareQuantity,
                remainingShareQuantity,
                action.getIssuePrice(),
                action.getDividendAmount(),
                action.getStatus(),
                action.getBasePrice(),
                action.getTheoreticalExRightsPrice(),
                action.getExRightsDate(),
                action.getRecordDate(),
                action.getEntitlementCloseCycleId(),
                action.getEntitlementCloseRunId(),
                action.getPaymentDate(),
                action.getListingDate(),
                action.getDelistingDate(),
                action.getDelistingTreatment() == null ? null : action.getDelistingTreatment().name(),
                action.getOfferingType(),
                action.getSubscriptionStartDate(),
                action.getSubscriptionEndDate(),
                action.getAppliedAt(),
                action.getPaidAt(),
                action.getListedAt(),
                action.getSplitFrom(),
                action.getSplitTo(),
                action.getDescription(),
                action.getCreatedAt()
        );
    }

    private CorporateActionEntitlementResponse toCorporateActionEntitlementResponse(
            StockCorporateActionEntitlement entitlement,
            StockCorporateAction action
    ) {
        return new CorporateActionEntitlementResponse(
                entitlement.getId(),
                entitlement.getAccountId(),
                entitlement.getActionId(),
                entitlement.getSymbol(),
                action == null ? null : action.getActionType(),
                entitlement.getQuantity() == null ? 0L : entitlement.getQuantity(),
                entitlement.getShareQuantity(),
                entitlement.getCashAmount(),
                entitlement.getSubscribedShareQuantity(),
                entitlement.getSubscribedCashAmount(),
                entitlement.getForfeitedShareQuantity(),
                entitlement.getStatus(),
                entitlement.getCreatedAt(),
                entitlement.getSubscribedAt(),
                entitlement.getPaidAt()
        );
    }

}
