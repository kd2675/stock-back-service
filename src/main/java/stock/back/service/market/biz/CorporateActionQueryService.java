package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlement;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.CorporateActionEntitlementResponse;
import stock.back.service.market.vo.CorporateActionResponse;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CorporateActionQueryService {

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
        return stockCorporateActionRepository.findBySymbolOrderByCreatedAtDesc(normalizedSymbol).stream()
                .map(this::toCorporateActionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CorporateActionEntitlementResponse> getMyCorporateActionEntitlements(String userKey) {
        Long accountId = stockAccountRepository.findByUserKeyAndStatus(userKey, StockAccountStatus.ACTIVE)
                .map(stock.back.service.database.entity.StockAccount::getId)
                .orElse(null);
        if (accountId == null) {
            return List.of();
        }
        List<StockCorporateActionEntitlement> entitlements =
                stockCorporateActionEntitlementRepository.findTop50ByAccountIdOrderByCreatedAtDesc(accountId);
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

    private CorporateActionResponse toCorporateActionResponse(StockCorporateAction action) {
        return new CorporateActionResponse(
                action.getId(),
                action.getSymbol(),
                action.getActionType(),
                action.getShareQuantity(),
                action.getIssuePrice(),
                action.getDividendAmount(),
                action.getStatus(),
                action.getBasePrice(),
                action.getTheoreticalExRightsPrice(),
                action.getExRightsDate(),
                action.getPaymentDate(),
                action.getListingDate(),
                action.getDelistingDate(),
                action.getDelistingTreatment() == null ? null : action.getDelistingTreatment().name(),
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
                entitlement.getStatus(),
                entitlement.getCreatedAt(),
                entitlement.getPaidAt()
        );
    }

}
