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
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.biz.SimulationClockService;
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
    private final SimulationClockService simulationClockService;
    private final OrderBookReadySymbolPublisher orderBookReadySymbolPublisher;

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
        tradingMarketRuleService.validateMarketOpen(symbol, marketType);
        tradingMarketRuleService.validateLimitPriceRule(symbol, marketType, request.orderType(), request.limitPrice());

        BigDecimal reservedCash = tradingMarketRuleService.calculateReservedCash(request, symbol);
        StockAccount account = accountService.requireAccountForUpdate(userKey);
        LocalDateTime orderedAt = simulationClockService.currentMarketDateTime();

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
                orderedAt
        );

        StockOrder savedOrder = stockOrderRepository.save(order);
        orderBookReadySymbolPublisher.enqueueAfterCommit(savedOrder.getSymbol(), savedOrder.getMarketType());
        return TradingResponseMapper.toOrderResponse(savedOrder);
    }

    @Transactional
    public OrderResponse cancelOrder(String userKey, Long orderId) {
        StockAccount account = accountService.requireAccountForUpdate(userKey);
        StockOrder order = stockOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> StockException.notFound("Order not found"));
        if (!order.getAccountId().equals(account.getId())) {
            throw StockException.notFound("Order not found");
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw StockException.conflict("Only pending orders can be cancelled");
        }
        LocalDateTime cancelledAt = simulationClockService.currentMarketDateTime();
        tradingReservationService.releaseOnCancel(userKey, account.getId(), order, cancelledAt);
        order.cancel(cancelledAt);
        return TradingResponseMapper.toOrderResponse(order);
    }

    @Transactional
    public OrderResponse amendOrder(String userKey, Long orderId, OrderAmendRequest request) {
        StockAccount account = accountService.requireAccountForUpdate(userKey);
        StockOrder order = findOwnOpenOrderForUpdate(account.getId(), orderId);
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

        LocalDateTime amendedAt = simulationClockService.currentMarketDateTime();
        if (order.getSide() == OrderSide.BUY) {
            tradingReservationService.amendBuyLimitOrder(userKey, order, nextQuantity, nextLimitPrice, amendedAt);
        } else {
            tradingReservationService.amendSellLimitOrder(account.getId(), order, nextQuantity, nextLimitPrice, amendedAt);
        }
        orderBookReadySymbolPublisher.enqueueAfterCommit(order.getSymbol(), order.getMarketType());
        return TradingResponseMapper.toOrderResponse(order);
    }

    @Transactional
    public OrderResponse cancelOrderPartially(String userKey, Long orderId, OrderCancelRequest request) {
        StockAccount account = accountService.requireAccountForUpdate(userKey);
        StockOrder order = findOwnOpenOrderForUpdate(account.getId(), orderId);
        if (request == null || request.quantity() == null || request.quantity() <= 0) {
            throw StockException.badRequest("Cancel quantity must be positive");
        }

        long remainingQuantity = order.getQuantity() - order.getFilledQuantity();
        if (request.quantity() > remainingQuantity) {
            throw StockException.badRequest("Cancel quantity cannot exceed remaining quantity");
        }
        if (request.quantity() == remainingQuantity) {
            LocalDateTime cancelledAt = simulationClockService.currentMarketDateTime();
            tradingReservationService.releaseAllRemainingReservation(userKey, account.getId(), order, cancelledAt);
            order.cancel(cancelledAt);
            return TradingResponseMapper.toOrderResponse(order);
        }

        tradingReservationService.releasePartialReservation(
                userKey,
                account.getId(),
                order,
                request.quantity(),
                remainingQuantity,
                simulationClockService.currentMarketDateTime()
        );
        return TradingResponseMapper.toOrderResponse(order);
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

    private StockOrder findOwnOpenOrderForUpdate(Long accountId, Long orderId) {
        StockOrder order = stockOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> StockException.notFound("Order not found"));
        if (!order.getAccountId().equals(accountId)) {
            throw StockException.notFound("Order not found");
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw StockException.conflict("Only pending orders can be changed");
        }
        return order;
    }

}
