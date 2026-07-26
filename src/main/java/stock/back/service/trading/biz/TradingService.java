package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.entity.StockOrder;
import stock.back.service.database.entity.StockOrderOriginType;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.trading.vo.ExecutionResponse;
import stock.back.service.trading.vo.FundFlowResponse;
import stock.back.service.trading.vo.HoldingResponse;
import stock.back.service.trading.vo.OrderAmendRequest;
import stock.back.service.trading.vo.OrderCancelRequest;
import stock.back.service.trading.vo.OrderRequest;
import stock.back.service.trading.vo.OrderResponse;
import stock.back.service.trading.vo.PortfolioResponse;
import stock.back.service.trading.vo.PortfolioSnapshotResponse;
import stock.back.service.trading.vo.ProfitSummaryResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TradingService {

    private final AccountService accountService;
    private final StockOrderRepository stockOrderRepository;
    private final TradingQueryService tradingQueryService;
    private final TradingMarketRuleService tradingMarketRuleService;
    private final TradingReservationService tradingReservationService;
    private final TradingSessionFenceService tradingSessionFenceService;
    private final OrderBookReadySymbolPublisher orderBookReadySymbolPublisher;
    private final AutoParticipantFundingBudgetReleaseService fundingBudgetReleaseService;

    @Transactional
    public OrderResponse placeOrder(String userKey, OrderRequest request) {
        String symbol = TradingOrderRequestPolicy.normalizeSymbol(request);
        TradingOrderRequestPolicy.validateOrderRequest(request, symbol);
        String clientOrderId = TradingOrderRequestPolicy.normalizeClientOrderId(request);
        Optional<OrderResponse> existingOrder = findExistingClientOrder(userKey, clientOrderId);
        if (existingOrder.isPresent()) {
            return existingOrder.get();
        }

        MarketType marketType = TradingOrderRequestPolicy.normalizeMarketType(request);
        tradingMarketRuleService.validateSymbolExists(symbol, marketType);
        tradingMarketRuleService.validateLimitPriceRule(symbol, marketType, request.orderType(), request.limitPrice());

        BigDecimal reservedCash = tradingMarketRuleService.calculateReservedCash(request, symbol);
        TradingSessionFenceService.TradingSessionApproval sessionApproval =
                tradingSessionFenceService.acquireOpenSession(symbol, marketType);
        StockAccount account = accountService.requireAccountForUpdate(userKey);
        if (account.getParticipantCategory() == null
                || !account.getParticipantCategory().canSubmitUserOrders()) {
            throw StockException.conflict(
                    "Account role cannot submit user orders: "
                            + (account.getParticipantCategory() == null
                                    ? "UNKNOWN"
                                    : account.getParticipantCategory().name())
            );
        }
        LocalDateTime orderedAt = sessionApproval.businessEffectiveAt();

        if (request.side() == OrderSide.BUY) {
            existingOrder = findExistingClientOrder(account.getId(), clientOrderId);
            if (existingOrder.isPresent()) {
                return existingOrder.get();
            }
            tradingReservationService.reserveBuyOrder(account, reservedCash, orderedAt);
        } else {
            StockHolding holding = tradingReservationService.findSellHoldingForUpdate(account.getId(), symbol);
            existingOrder = findExistingClientOrder(account.getId(), clientOrderId);
            if (existingOrder.isPresent()) {
                return existingOrder.get();
            }
            tradingReservationService.reserveSellOrder(holding, request.quantity(), orderedAt);
        }

        StockOrder order = StockOrder.pending(
                clientOrderId,
                account.getId(),
                symbol,
                marketType,
                request.side(),
                request.orderType(),
                request.orderType() == OrderType.LIMIT ? request.limitPrice() : null,
                request.quantity(),
                reservedCash,
                StockOrderOriginType.MANUAL_PARTICIPANT,
                account.resolveSelfTradeGroupId(),
                orderedAt
        );

        StockOrder savedOrder = stockOrderRepository.save(order);
        orderBookReadySymbolPublisher.enqueueAfterCommit(savedOrder.getSymbol(), savedOrder.getMarketType());
        return TradingResponseMapper.toOrderResponse(savedOrder);
    }

    @Transactional
    public OrderResponse cancelOrder(String userKey, Long orderId) {
        TradingSessionFenceService.OwnedOrderSessionApproval sessionApproval =
                tradingSessionFenceService.acquireOwnedOpenOrderMutationSession(
                        userKey,
                        orderId,
                        "Only pending orders can be cancelled"
                );
        StockAccount account = accountService.requireAccountForUpdate(userKey);
        StockHolding sellHolding = lockSellHoldingBeforeOrder(account, sessionApproval);
        StockOrder order = stockOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> StockException.notFound("Order not found"));
        validateLockedOrder(account, order, sessionApproval, "Only pending orders can be cancelled");
        LocalDateTime cancelledAt = sessionApproval.businessEffectiveAt();
        tradingReservationService.releaseOnCancel(account, sellHolding, order, cancelledAt);
        releaseFundingBudgetOnFullCancel(order, cancelledAt);
        order.cancel(cancelledAt);
        return TradingResponseMapper.toOrderResponse(order);
    }

    @Transactional
    public OrderResponse amendOrder(String userKey, Long orderId, OrderAmendRequest request) {
        TradingSessionFenceService.OwnedOrderSessionApproval sessionApproval =
                tradingSessionFenceService.acquireOwnedOpenOrderEntrySession(userKey, orderId);
        StockAccount account = accountService.requireAccountForUpdate(userKey);
        StockHolding sellHolding = lockSellHoldingBeforeOrder(account, sessionApproval);
        StockOrder order = findOwnOpenOrderForUpdate(account, orderId, sessionApproval);
        rejectManualBudgetBackedOrderMutation(order, "amended");
        if (request == null || (request.quantity() == null && request.limitPrice() == null)) {
            throw StockException.badRequest("Order amendment requires quantity or limit price");
        }
        if (order.getOrderType() != OrderType.LIMIT) {
            throw StockException.conflict("Only limit orders can be amended");
        }

        BigDecimal nextLimitPrice = request.limitPrice() == null ? order.getLimitPrice() : request.limitPrice();
        if (nextLimitPrice == null || nextLimitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Limit price must be positive");
        }
        tradingMarketRuleService.validateLimitPriceRule(order.getSymbol(), order.getMarketType(), order.getOrderType(), nextLimitPrice);

        long nextQuantity = request.quantity() == null ? order.getQuantity() : request.quantity();
        if (nextQuantity <= order.getFilledQuantity()) {
            throw StockException.badRequest("Amended quantity must be greater than filled quantity");
        }

        LocalDateTime amendedAt = sessionApproval.businessEffectiveAt();
        if (order.getSide() == OrderSide.BUY) {
            tradingReservationService.amendBuyLimitOrder(account, order, nextQuantity, nextLimitPrice, amendedAt);
        } else {
            tradingReservationService.amendSellLimitOrder(sellHolding, order, nextQuantity, nextLimitPrice, amendedAt);
        }
        orderBookReadySymbolPublisher.enqueueAfterCommit(order.getSymbol(), order.getMarketType());
        return TradingResponseMapper.toOrderResponse(order);
    }

    @Transactional
    public OrderResponse cancelOrderPartially(String userKey, Long orderId, OrderCancelRequest request) {
        TradingSessionFenceService.OwnedOrderSessionApproval sessionApproval =
                tradingSessionFenceService.acquireOwnedOpenOrderMutationSession(
                        userKey,
                        orderId,
                        "Only pending orders can be changed"
                );
        StockAccount account = accountService.requireAccountForUpdate(userKey);
        StockHolding sellHolding = lockSellHoldingBeforeOrder(account, sessionApproval);
        StockOrder order = findOwnOpenOrderForUpdate(account, orderId, sessionApproval);
        if (request == null || request.quantity() == null || request.quantity() <= 0) {
            throw StockException.badRequest("Cancel quantity must be positive");
        }

        long remainingQuantity = order.getQuantity() - order.getFilledQuantity();
        if (request.quantity() > remainingQuantity) {
            throw StockException.badRequest("Cancel quantity cannot exceed remaining quantity");
        }
        if (request.quantity() == remainingQuantity) {
            LocalDateTime cancelledAt = sessionApproval.businessEffectiveAt();
            tradingReservationService.releaseAllRemainingReservation(account, sellHolding, order, cancelledAt);
            releaseFundingBudgetOnFullCancel(order, cancelledAt);
            order.cancel(cancelledAt);
            return TradingResponseMapper.toOrderResponse(order);
        }

        rejectManualBudgetBackedOrderMutation(order, "partially cancelled");

        tradingReservationService.releasePartialReservation(
                account,
                sellHolding,
                order,
                request.quantity(),
                remainingQuantity,
                sessionApproval.businessEffectiveAt()
        );
        return TradingResponseMapper.toOrderResponse(order);
    }

    private void releaseFundingBudgetOnFullCancel(StockOrder order, LocalDateTime cancelledAt) {
        if (order.getFundingBudgetType() != null) {
            fundingBudgetReleaseService.releaseCancelledOrderBudgets(List.of(order.getId()), cancelledAt);
        }
    }

    private void rejectManualBudgetBackedOrderMutation(StockOrder order, String operation) {
        if (order.getFundingBudgetType() != null) {
            throw StockException.conflict(
                    "A dedicated-funding order cannot be %s; cancel the remaining order instead"
                            .formatted(operation)
            );
        }
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrders(String userKey, MarketType marketType) {
        return tradingQueryService.getOrders(userKey, marketType);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrders(String userKey, MarketType marketType, String symbol, Integer limit) {
        return tradingQueryService.getOrders(userKey, marketType, symbol, limit);
    }

    @Transactional(readOnly = true)
    public List<ExecutionResponse> getExecutions(String userKey, ExecutionSource source) {
        return tradingQueryService.getExecutions(userKey, source);
    }

    @Transactional(readOnly = true)
    public List<ExecutionResponse> getExecutions(String userKey, ExecutionSource source, String symbol, Integer limit) {
        return tradingQueryService.getExecutions(userKey, source, symbol, limit);
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> getHoldings(String userKey) {
        return tradingQueryService.getHoldings(userKey);
    }

    @Transactional(readOnly = true)
    public PortfolioResponse getPortfolio(String userKey) {
        return tradingQueryService.getPortfolio(userKey);
    }

    @Transactional(readOnly = true)
    public List<PortfolioSnapshotResponse> getPortfolioSnapshots(String userKey) {
        return tradingQueryService.getPortfolioSnapshots(userKey);
    }

    @Transactional(readOnly = true)
    public ProfitSummaryResponse getProfitSummary(String userKey) {
        return tradingQueryService.getProfitSummary(userKey);
    }

    @Transactional(readOnly = true)
    public FundFlowResponse getFundFlow(String userKey) {
        return tradingQueryService.getFundFlow(userKey);
    }

    private Optional<OrderResponse> findExistingClientOrder(Long accountId, String clientOrderId) {
        return stockOrderRepository.findByClientOrderId(clientOrderId)
                .map(order -> {
                    if (!order.getAccountId().equals(accountId)) {
                        throw StockException.conflict("Client order id already exists");
                    }
                    return TradingResponseMapper.toOrderResponse(order);
                });
    }

    private Optional<OrderResponse> findExistingClientOrder(String userKey, String clientOrderId) {
        return stockOrderRepository.findByClientOrderId(clientOrderId)
                .map(order -> {
                    Long accountId = accountService.findAccount(userKey)
                            .map(StockAccount::getId)
                            .orElse(null);
                    if (!order.getAccountId().equals(accountId)) {
                        throw StockException.conflict("Client order id already exists");
                    }
                    return TradingResponseMapper.toOrderResponse(order);
                });
    }

    private StockHolding lockSellHoldingBeforeOrder(
            StockAccount account,
            TradingSessionFenceService.OwnedOrderSessionApproval sessionApproval
    ) {
        if (sessionApproval.orderSide() != OrderSide.SELL) {
            return null;
        }
        return tradingReservationService.findSellHoldingForUpdate(account.getId(), sessionApproval.symbol());
    }

    private StockOrder findOwnOpenOrderForUpdate(
            StockAccount account,
            Long orderId,
            TradingSessionFenceService.OwnedOrderSessionApproval sessionApproval
    ) {
        StockOrder order = stockOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> StockException.notFound("Order not found"));
        validateLockedOrder(account, order, sessionApproval, "Only pending orders can be changed");
        return order;
    }

    private void validateLockedOrder(
            StockAccount account,
            StockOrder order,
            TradingSessionFenceService.OwnedOrderSessionApproval sessionApproval,
            String unavailableOrderMessage
    ) {
        if (!order.getAccountId().equals(account.getId())
                || !order.getSymbol().equals(sessionApproval.symbol())
                || order.getMarketType() != sessionApproval.marketType()
                || order.getSide() != sessionApproval.orderSide()) {
            throw StockException.notFound("Order not found");
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw StockException.conflict(unavailableOrderMessage);
        }
    }

}
