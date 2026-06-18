package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockExecution;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.entity.StockOrder;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockExecutionRepository;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.trading.vo.ExecutionResponse;
import stock.back.service.trading.vo.HoldingResponse;
import stock.back.service.trading.vo.OrderRequest;
import stock.back.service.trading.vo.OrderResponse;
import stock.back.service.trading.vo.PortfolioResponse;
import stock.back.service.trading.vo.PortfolioSnapshotResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TradingService {

    private static final int CLIENT_ORDER_ID_MAX_LENGTH = 64;
    private static final Pattern CLIENT_ORDER_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");

    private final AccountService accountService;
    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockOrderRepository stockOrderRepository;
    private final StockHoldingRepository stockHoldingRepository;
    private final StockExecutionRepository stockExecutionRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final StockPriceCacheService stockPriceCacheService;

    @Transactional
    public OrderResponse placeOrder(String userKey, OrderRequest request) {
        String symbol = normalizeSymbol(request);
        validateOrderRequest(request, symbol);
        String clientOrderId = normalizeClientOrderId(request);

        Optional<OrderResponse> existingOrder = findExistingClientOrder(userKey, clientOrderId);
        if (existingOrder.isPresent()) {
            return existingOrder.get();
        }

        if (!stockInstrumentRepository.existsById(symbol)) {
            throw StockException.notFound("Unknown stock symbol: " + symbol);
        }

        BigDecimal reservedCash = calculateReservedCash(request, symbol);

        if (request.side() == OrderSide.BUY) {
            StockAccount account = accountService.getOrOpenAccountForUpdate(userKey);
            existingOrder = findExistingClientOrder(userKey, clientOrderId);
            if (existingOrder.isPresent()) {
                return existingOrder.get();
            }
            if (account.getCashBalance().compareTo(reservedCash) < 0) {
                throw StockException.conflict("Not enough cash balance");
            }
            account.reserveCash(reservedCash);
        } else {
            StockHolding holding = stockHoldingRepository.findByUserKeyAndSymbolForUpdate(userKey, symbol)
                    .orElseThrow(() -> StockException.conflict("Not enough holding quantity"));
            existingOrder = findExistingClientOrder(userKey, clientOrderId);
            if (existingOrder.isPresent()) {
                return existingOrder.get();
            }
            if (holding.getAvailableQuantity() < request.quantity()) {
                throw StockException.conflict("Not enough holding quantity");
            }
            holding.reserveQuantity(request.quantity());
        }

        StockOrder order = StockOrder.pending(
                clientOrderId,
                userKey,
                symbol,
                request.side(),
                request.orderType(),
                request.orderType() == OrderType.LIMIT ? request.limitPrice() : null,
                request.quantity(),
                reservedCash
        );

        return toOrderResponse(stockOrderRepository.save(order));
    }

    @Transactional
    public OrderResponse cancelOrder(String userKey, Long orderId) {
        StockOrder order = stockOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> StockException.notFound("Order not found"));
        if (!order.getUserKey().equals(userKey)) {
            throw StockException.notFound("Order not found");
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw StockException.conflict("Only pending orders can be cancelled");
        }
        if (order.getSide() == OrderSide.BUY && order.getReservedCash().compareTo(BigDecimal.ZERO) > 0) {
            StockAccount account = accountService.getOrOpenAccountForUpdate(userKey);
            account.releaseCash(order.getReservedCash());
        }
        if (order.getSide() == OrderSide.SELL) {
            stockHoldingRepository.findByUserKeyAndSymbolForUpdate(userKey, order.getSymbol())
                    .ifPresent(holding -> holding.releaseReservedQuantity(order.getQuantity() - order.getFilledQuantity()));
        }
        order.cancel();
        return toOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrders(String userKey) {
        return stockOrderRepository.findTop50ByUserKeyOrderByCreatedAtDesc(userKey).stream()
                .map(this::toOrderResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExecutionResponse> getExecutions(String userKey) {
        return stockExecutionRepository.findTop50ByUserKeyOrderByExecutedAtDesc(userKey).stream()
                .map(this::toExecutionResponse)
                .toList();
    }

    @Transactional
    public List<HoldingResponse> getHoldings(String userKey) {
        accountService.getOrOpenAccount(userKey);
        return buildHoldingResponses(userKey);
    }

    private List<HoldingResponse> buildHoldingResponses(String userKey) {
        return stockHoldingRepository.findByUserKeyOrderBySymbolAsc(userKey).stream()
                .map(this::toHoldingResponse)
                .toList();
    }

    @Transactional
    public PortfolioResponse getPortfolio(String userKey) {
        StockAccount account = accountService.getOrOpenAccount(userKey);
        List<HoldingResponse> holdings = buildHoldingResponses(userKey);
        BigDecimal marketValue = holdings.stream()
                .map(HoldingResponse::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<OrderStatus> activeOrderStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        BigDecimal reservedBuyCash = stockOrderRepository.sumReservedCashByUserKeyAndSideAndStatusIn(
                userKey,
                OrderSide.BUY,
                activeOrderStatuses
        );
        BigDecimal totalAsset = account.getCashBalance().add(reservedBuyCash).add(marketValue);
        BigDecimal returnRate = BigDecimal.ZERO;
        if (account.getInitialCash().compareTo(BigDecimal.ZERO) > 0) {
            returnRate = totalAsset.subtract(account.getInitialCash())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(account.getInitialCash(), 4, RoundingMode.HALF_UP);
        }
        long pendingCount = stockOrderRepository.countByUserKeyAndStatusIn(
                userKey,
                activeOrderStatuses
        );
        return new PortfolioResponse(
                accountService.toResponse(account),
                marketValue,
                reservedBuyCash,
                totalAsset,
                returnRate,
                pendingCount,
                holdings
        );
    }

    @Transactional(readOnly = true)
    public List<PortfolioSnapshotResponse> getPortfolioSnapshots(String userKey) {
        accountService.getOrOpenAccount(userKey);
        return portfolioSnapshotRepository.findTop30ByUserKeyOrderBySnapshotDateDesc(userKey).stream()
                .map(this::toPortfolioSnapshotResponse)
                .toList();
    }

    private String normalizeSymbol(OrderRequest request) {
        if (request == null || request.symbol() == null) {
            return "";
        }
        return request.symbol().trim().toUpperCase(Locale.ROOT);
    }

    private void validateOrderRequest(OrderRequest request, String symbol) {
        if (request == null) {
            throw StockException.badRequest("Order request is required");
        }
        if (symbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (request.side() == null) {
            throw StockException.badRequest("Order side is required");
        }
        if (request.orderType() == null) {
            throw StockException.badRequest("Order type is required");
        }
        if (request.quantity() <= 0) {
            throw StockException.badRequest("Quantity must be positive");
        }
        if (request.orderType() == OrderType.LIMIT) {
            if (request.limitPrice() == null) {
                throw StockException.badRequest("Limit price is required for limit orders");
            }
            if (request.limitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw StockException.badRequest("Limit price must be positive");
            }
        }
    }

    private String normalizeClientOrderId(OrderRequest request) {
        if (request.clientOrderId() == null || request.clientOrderId().isBlank()) {
            return UUID.randomUUID().toString();
        }
        String clientOrderId = request.clientOrderId().trim();
        if (clientOrderId.length() > CLIENT_ORDER_ID_MAX_LENGTH) {
            throw StockException.badRequest("Client order id must be 64 characters or less");
        }
        if (!CLIENT_ORDER_ID_PATTERN.matcher(clientOrderId).matches()) {
            throw StockException.badRequest("Client order id contains invalid characters");
        }
        return clientOrderId;
    }

    private Optional<OrderResponse> findExistingClientOrder(String userKey, String clientOrderId) {
        return stockOrderRepository.findByClientOrderId(clientOrderId)
                .map(order -> {
                    if (!order.getUserKey().equals(userKey)) {
                        throw StockException.conflict("Client order id already exists");
                    }
                    return toOrderResponse(order);
                });
    }

    private BigDecimal calculateReservedCash(OrderRequest request, String symbol) {
        if (request.side() == OrderSide.SELL) {
            return BigDecimal.ZERO;
        }
        BigDecimal price = request.orderType() == OrderType.MARKET ? resolveReferencePrice(symbol) : request.limitPrice();
        return price.multiply(BigDecimal.valueOf(request.quantity()));
    }

    private BigDecimal resolveReferencePrice(String symbol) {
        return resolveCurrentPrice(symbol)
                .orElseThrow(() -> StockException.notFound("Price not found: " + symbol));
    }

    private HoldingResponse toHoldingResponse(StockHolding holding) {
        BigDecimal currentPrice = resolveCurrentPrice(holding.getSymbol()).orElse(holding.getAveragePrice());
        BigDecimal marketValue = currentPrice.multiply(BigDecimal.valueOf(holding.getQuantity()));
        BigDecimal cost = holding.getAveragePrice().multiply(BigDecimal.valueOf(holding.getQuantity()));
        return new HoldingResponse(
                holding.getSymbol(),
                holding.getQuantity(),
                holding.getReservedQuantity() == null ? 0L : holding.getReservedQuantity(),
                holding.getAvailableQuantity(),
                holding.getAveragePrice(),
                currentPrice,
                marketValue,
                marketValue.subtract(cost)
        );
    }

    private java.util.Optional<BigDecimal> resolveCurrentPrice(String symbol) {
        return stockPriceCacheService.getCachedPrice(symbol)
                .map(CachedStockPrice::currentPrice)
                .or(() -> stockPriceRepository.findById(symbol).map(StockPrice::getCurrentPrice));
    }

    private OrderResponse toOrderResponse(StockOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getClientOrderId(),
                order.getSymbol(),
                order.getSide(),
                order.getOrderType(),
                order.getStatus(),
                order.getLimitPrice(),
                order.getQuantity(),
                order.getFilledQuantity(),
                order.getAverageFillPrice(),
                order.getReservedCash(),
                order.getCreatedAt()
        );
    }

    private ExecutionResponse toExecutionResponse(StockExecution execution) {
        return new ExecutionResponse(
                execution.getId(),
                execution.getOrderId(),
                execution.getSymbol(),
                execution.getSide(),
                execution.getQuantity(),
                execution.getPrice(),
                execution.getSource(),
                execution.getExecutedAt()
        );
    }

    private PortfolioSnapshotResponse toPortfolioSnapshotResponse(PortfolioSnapshot snapshot) {
        return new PortfolioSnapshotResponse(
                snapshot.getSnapshotDate(),
                snapshot.getTotalAsset(),
                snapshot.getCashBalance(),
                snapshot.getMarketValue(),
                snapshot.getReturnRate()
        );
    }
}
