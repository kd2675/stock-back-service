package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlementStatus;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.CashDividendGuidanceResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CashDividendGuidanceQueryService {

    private static final int HISTORY_LIMIT = 5;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final String PREVIOUS_CLOSE = "PREVIOUS_CLOSE";
    private static final String CURRENT_PRICE = "CURRENT_PRICE";
    private static final Comparator<StockCorporateAction> DIVIDEND_HISTORY_ORDER = Comparator
            .comparing(
                    StockCorporateAction::getExRightsDate,
                    Comparator.nullsLast(Comparator.reverseOrder())
            )
            .thenComparing(
                    StockCorporateAction::getId,
                    Comparator.nullsLast(Comparator.reverseOrder())
            );

    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockCorporateActionRepository stockCorporateActionRepository;
    private final StockCorporateActionEntitlementRepository stockCorporateActionEntitlementRepository;
    private final CashDividendGuidanceSnapshotReader cashDividendGuidanceSnapshotReader;

    @Transactional(readOnly = true)
    public CashDividendGuidanceResponse getGuidance(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Unknown order book symbol: " + normalizedSymbol));
        StockPrice price = stockPriceRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Price not found: " + normalizedSymbol));
        PriceReference priceReference = priceReference(price);
        List<StockCorporateAction> actions = stockCorporateActionRepository
                .findBySymbolOrderByCreatedAtDesc(normalizedSymbol);
        List<StockCorporateAction> completedDividends = actions.stream()
                .filter(action -> action.getActionType() == StockCorporateActionType.CASH_DIVIDEND)
                .filter(action -> action.getStatus() == StockCorporateActionStatus.PAID)
                .filter(action -> action.getDividendAmount() != null && action.getDividendAmount().signum() > 0)
                .sorted(DIVIDEND_HISTORY_ORDER)
                .toList();
        List<StockCorporateAction> recentDividends = completedDividends.stream()
                .limit(HISTORY_LIMIT)
                .toList();
        Map<Long, PaidDividendSummary> paidSummaryByActionId = paidSummaryByActionId(recentDividends);
        List<CashDividendGuidanceResponse.DividendHistory> history = recentDividends.stream()
                .map(action -> toHistory(
                        action,
                        actions,
                        paidSummaryByActionId.getOrDefault(action.getId(), PaidDividendSummary.EMPTY)
                ))
                .toList();
        CashDividendGuidanceSnapshotReader.HoldingReference holdingReference = cashDividendGuidanceSnapshotReader
                .findLatestCompletedFullMarketHolding(normalizedSymbol)
                .orElse(null);
        return new CashDividendGuidanceResponse(
                normalizedSymbol,
                priceReference.price(),
                priceReference.basis(),
                instrument.getIssuedShares(),
                instrument.getTradableShares(),
                holdingReference == null ? null : holdingReference.holdingQuantity(),
                holdingReference == null ? null : holdingReference.closeRunId(),
                holdingReference == null ? null : holdingReference.businessDate(),
                completedDividends.size(),
                history
        );
    }

    private PriceReference priceReference(StockPrice price) {
        if (price.getPreviousClose() != null && price.getPreviousClose().signum() > 0) {
            return new PriceReference(price.getPreviousClose(), PREVIOUS_CLOSE);
        }
        if (price.getCurrentPrice() != null && price.getCurrentPrice().signum() > 0) {
            return new PriceReference(price.getCurrentPrice(), CURRENT_PRICE);
        }
        throw StockException.conflict("A positive reference price is required for cash dividend guidance: " + price.getSymbol());
    }

    private Map<Long, PaidDividendSummary> paidSummaryByActionId(List<StockCorporateAction> actions) {
        List<Long> actionIds = actions.stream()
                .map(StockCorporateAction::getId)
                .filter(id -> id != null)
                .toList();
        if (actionIds.isEmpty()) {
            return Map.of();
        }
        return stockCorporateActionEntitlementRepository.sumCashAmountByActionIdInAndStatus(
                        actionIds,
                        StockCorporateActionEntitlementStatus.PAID
                ).stream()
                .collect(Collectors.toMap(
                        StockCorporateActionEntitlementRepository.PaidCashAmountSummary::getActionId,
                        summary -> new PaidDividendSummary(
                                summary.getCashAmount() == null ? BigDecimal.ZERO : summary.getCashAmount(),
                                summary.getEligibleShareQuantity() == null ? 0L : summary.getEligibleShareQuantity()
                        ),
                        PaidDividendSummary::add
                ));
    }

    private CashDividendGuidanceResponse.DividendHistory toHistory(
            StockCorporateAction dividend,
            List<StockCorporateAction> actions,
            PaidDividendSummary paidSummary
    ) {
        return new CashDividendGuidanceResponse.DividendHistory(
                dividend.getId(),
                dividend.getStatus(),
                dividend.getDividendAmount(),
                splitAdjustedDividendAmount(dividend, actions),
                dividend.getBasePrice(),
                percentage(dividend.getDividendAmount(), dividend.getBasePrice()),
                paidSummary.cashAmount(),
                paidSummary.eligibleShareQuantity(),
                dividend.getExRightsDate(),
                dividend.getPaymentDate()
        );
    }

    private BigDecimal splitAdjustedDividendAmount(
            StockCorporateAction dividend,
            List<StockCorporateAction> actions
    ) {
        BigDecimal adjustment = BigDecimal.ONE;
        for (StockCorporateAction action : actions) {
            if (action.getActionType() != StockCorporateActionType.STOCK_SPLIT
                    || action.getStatus() != StockCorporateActionStatus.LISTED
                    || action.getListingDate() == null
                    || dividend.getExRightsDate() == null
                    || !action.getListingDate().isAfter(dividend.getExRightsDate())
                    || action.getSplitFrom() == null
                    || action.getSplitTo() == null
                    || action.getSplitFrom() <= 0
                    || action.getSplitTo() <= action.getSplitFrom()) {
                continue;
            }
            adjustment = adjustment.multiply(BigDecimal.valueOf(action.getSplitFrom()))
                    .divide(BigDecimal.valueOf(action.getSplitTo()), 12, RoundingMode.HALF_UP);
        }
        return dividend.getDividendAmount()
                .multiply(adjustment)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.multiply(ONE_HUNDRED)
                .divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private record PriceReference(BigDecimal price, String basis) {
    }

    private record PaidDividendSummary(BigDecimal cashAmount, long eligibleShareQuantity) {

        private static final PaidDividendSummary EMPTY = new PaidDividendSummary(BigDecimal.ZERO, 0L);

        private PaidDividendSummary add(PaidDividendSummary other) {
            return new PaidDividendSummary(
                    cashAmount.add(other.cashAmount),
                    Math.addExact(eligibleShareQuantity, other.eligibleShareQuantity)
            );
        }
    }
}
