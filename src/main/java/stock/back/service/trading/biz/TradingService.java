package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockExecution;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.entity.StockOrder;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.entity.StockVirtualMarketConfig;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockExecutionRepository;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.database.repository.StockVirtualMarketConfigRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.trading.vo.ExecutionResponse;
import stock.back.service.trading.vo.HoldingResponse;
import stock.back.service.trading.vo.OrderAmendRequest;
import stock.back.service.trading.vo.OrderCancelRequest;
import stock.back.service.trading.vo.OrderRequest;
import stock.back.service.trading.vo.OrderResponse;
import stock.back.service.trading.vo.PortfolioResponse;
import stock.back.service.trading.vo.PortfolioSnapshotResponse;
import stock.back.service.trading.vo.ProfitSummaryResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
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
    private static final BigDecimal DEFAULT_TICK_SIZE = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_PRICE_LIMIT_RATE = BigDecimal.valueOf(30);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final AccountService accountService;
    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockVirtualMarketConfigRepository stockVirtualMarketConfigRepository;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
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

        MarketType marketType = normalizeMarketType(request);
        validateSymbolExists(symbol, marketType);
        validateMarketOpen(symbol, marketType);
        validateLimitPriceRule(symbol, marketType, request.orderType(), request.limitPrice());

        BigDecimal reservedCash = calculateReservedCash(request, symbol);
        StockAccount account = accountService.requireAccountForUpdate(userKey);

        if (request.side() == OrderSide.BUY) {
            existingOrder = findExistingClientOrder(account.getId(), clientOrderId);
            if (existingOrder.isPresent()) {
                return existingOrder.get();
            }
            if (account.getCashBalance().compareTo(reservedCash) < 0) {
                throw StockException.conflict("Not enough cash balance");
            }
            account.reserveCash(reservedCash);
        } else {
            StockHolding holding = stockHoldingRepository.findByAccountIdAndSymbolForUpdate(account.getId(), symbol)
                    .orElseThrow(() -> StockException.conflict("Not enough holding quantity"));
            existingOrder = findExistingClientOrder(account.getId(), clientOrderId);
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
                account.getId(),
                symbol,
                marketType,
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
        StockAccount account = accountService.requireAccount(userKey);
        StockOrder order = stockOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> StockException.notFound("Order not found"));
        if (!order.getAccountId().equals(account.getId())) {
            throw StockException.notFound("Order not found");
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw StockException.conflict("Only pending orders can be cancelled");
        }
        if (order.getSide() == OrderSide.BUY && order.getReservedCash().compareTo(BigDecimal.ZERO) > 0) {
            accountService.requireAccountForUpdate(userKey).releaseCash(order.getReservedCash());
        }
        if (order.getSide() == OrderSide.SELL) {
            stockHoldingRepository.findByAccountIdAndSymbolForUpdate(account.getId(), order.getSymbol())
                    .ifPresent(holding -> holding.releaseReservedQuantity(order.getQuantity() - order.getFilledQuantity()));
        }
        order.cancel();
        return toOrderResponse(order);
    }

    @Transactional
    public OrderResponse amendOrder(String userKey, Long orderId, OrderAmendRequest request) {
        StockAccount account = accountService.requireAccount(userKey);
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
        validateLimitPriceRule(order.getSymbol(), order.getMarketType(), order.getOrderType(), nextLimitPrice);

        long nextQuantity = request.quantity() == null ? order.getQuantity() : request.quantity();
        if (nextQuantity <= order.getFilledQuantity()) {
            throw StockException.badRequest("Amended quantity must be greater than filled quantity");
        }

        if (order.getSide() == OrderSide.BUY) {
            amendBuyLimitOrder(userKey, order, nextQuantity, nextLimitPrice);
        } else {
            amendSellLimitOrder(account.getId(), order, nextQuantity, nextLimitPrice);
        }
        return toOrderResponse(order);
    }

    @Transactional
    public OrderResponse cancelOrderPartially(String userKey, Long orderId, OrderCancelRequest request) {
        StockAccount account = accountService.requireAccount(userKey);
        StockOrder order = findOwnOpenOrderForUpdate(account.getId(), orderId);
        if (request == null || request.quantity() == null || request.quantity() <= 0) {
            throw StockException.badRequest("Cancel quantity must be positive");
        }

        long remainingQuantity = order.getQuantity() - order.getFilledQuantity();
        if (request.quantity() > remainingQuantity) {
            throw StockException.badRequest("Cancel quantity cannot exceed remaining quantity");
        }
        if (request.quantity() == remainingQuantity) {
            releaseAllRemainingReservation(userKey, account.getId(), order);
            order.cancel();
            return toOrderResponse(order);
        }

        if (order.getSide() == OrderSide.BUY) {
            BigDecimal release = calculateReservedCashForCancel(order, request.quantity(), remainingQuantity);
            accountService.requireAccountForUpdate(userKey).releaseCash(release);
            order.reduceOpenQuantity(request.quantity(), order.getReservedCash().subtract(release).max(BigDecimal.ZERO));
        } else {
            StockHolding holding = stockHoldingRepository.findByAccountIdAndSymbolForUpdate(account.getId(), order.getSymbol())
                    .orElseThrow(() -> StockException.conflict("Not enough holding quantity"));
            holding.releaseReservedQuantity(request.quantity());
            order.reduceOpenQuantity(request.quantity(), BigDecimal.ZERO);
        }
        return toOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrders(String userKey, MarketType marketType) {
        Optional<StockAccount> account = accountService.findAccount(userKey);
        if (account.isEmpty()) {
            return Collections.emptyList();
        }
        List<StockOrder> orders = marketType == null
                ? stockOrderRepository.findTop50ByAccountIdOrderByCreatedAtDesc(account.get().getId())
                : stockOrderRepository.findTop50ByAccountIdAndMarketTypeOrderByCreatedAtDesc(account.get().getId(), marketType);
        return orders.stream()
                .map(this::toOrderResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExecutionResponse> getExecutions(String userKey, ExecutionSource source) {
        Optional<StockAccount> account = accountService.findAccount(userKey);
        if (account.isEmpty()) {
            return Collections.emptyList();
        }
        List<StockExecution> executions = source == null
                ? stockExecutionRepository.findTop50ByAccountIdOrderByExecutedAtDesc(account.get().getId())
                : stockExecutionRepository.findTop50ByAccountIdAndSourceOrderByExecutedAtDesc(account.get().getId(), source);
        return executions.stream()
                .map(this::toExecutionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> getHoldings(String userKey) {
        return accountService.findAccount(userKey)
                .map(account -> buildHoldingResponses(account.getId()))
                .orElseGet(Collections::emptyList);
    }

    private List<HoldingResponse> buildHoldingResponses(Long accountId) {
        return stockHoldingRepository.findByAccountIdOrderBySymbolAsc(accountId).stream()
                .map(this::toHoldingResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PortfolioResponse getPortfolio(String userKey) {
        StockAccount account = accountService.requireAccount(userKey);
        List<HoldingResponse> holdings = buildHoldingResponses(account.getId());
        BigDecimal marketValue = holdings.stream()
                .map(HoldingResponse::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<OrderStatus> activeOrderStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        BigDecimal reservedBuyCash = stockOrderRepository.sumReservedCashByAccountIdAndSideAndStatusIn(
                account.getId(),
                OrderSide.BUY,
                activeOrderStatuses
        );
        BigDecimal totalAsset = account.getCashBalance().add(reservedBuyCash).add(marketValue);
        BigDecimal returnRate = BigDecimal.ZERO;
        BigDecimal netCashFlow = accountService.getNetCashFlow(account.getId());
        if (netCashFlow.compareTo(BigDecimal.ZERO) > 0) {
            returnRate = totalAsset.subtract(netCashFlow)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(netCashFlow, 4, RoundingMode.HALF_UP);
        }
        long pendingCount = stockOrderRepository.countByAccountIdAndStatusIn(
                account.getId(),
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
        return accountService.findAccount(userKey)
                .map(account -> portfolioSnapshotRepository.findTop30ByAccountIdOrderBySnapshotDateDesc(account.getId()).stream()
                        .map(this::toPortfolioSnapshotResponse)
                        .toList())
                .orElseGet(Collections::emptyList);
    }

    @Transactional(readOnly = true)
    public ProfitSummaryResponse getProfitSummary(String userKey) {
        Optional<StockAccount> accountOptional = accountService.findAccount(userKey);
        if (accountOptional.isEmpty()) {
            return emptyProfitSummary();
        }
        StockAccount account = accountOptional.get();
        BigDecimal unrealizedProfit = buildHoldingResponses(account.getId()).stream()
                .map(HoldingResponse::unrealizedProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        StockExecutionRepository.ProfitSummaryProjection summary =
                stockExecutionRepository.summarizeProfitByAccountId(account.getId());
        BigDecimal realizedProfit = zeroIfNull(summary.getRealizedProfit());
        BigDecimal buyNetAmount = zeroIfNull(summary.getBuyNetAmount());
        BigDecimal sellNetAmount = zeroIfNull(summary.getSellNetAmount());

        return new ProfitSummaryResponse(
                realizedProfit,
                unrealizedProfit,
                realizedProfit.add(unrealizedProfit),
                zeroIfNull(summary.getTotalFeeAmount()),
                zeroIfNull(summary.getTotalTaxAmount()),
                zeroIfNull(summary.getBuyGrossAmount()),
                zeroIfNull(summary.getSellGrossAmount()),
                buyNetAmount,
                sellNetAmount,
                sellNetAmount.subtract(buyNetAmount),
                summary.getExecutionCount()
        );
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
        normalizeMarketType(request);
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

    private MarketType normalizeMarketType(OrderRequest request) {
        if (request == null || request.marketType() == null) {
            return MarketType.VIRTUAL_PRICE;
        }
        return request.marketType();
    }

    private void validateSymbolExists(String symbol, MarketType marketType) {
        boolean exists = marketType == MarketType.ORDER_BOOK
                ? stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue(symbol)
                : stockInstrumentRepository.existsById(symbol);
        if (!exists) {
            throw StockException.notFound("Unknown stock symbol: " + symbol);
        }
    }

    private void validateMarketOpen(String symbol, MarketType marketType) {
        if (marketType == MarketType.ORDER_BOOK) {
            StockOrderBookMarketConfig config = stockOrderBookMarketConfigRepository.findById(symbol)
                    .orElseThrow(() -> StockException.conflict("Market is not open: " + symbol));
            if (!Boolean.TRUE.equals(config.getEnabled()) || normalizeMarketSessionStatus(config.getMarketStatus()) != MarketSessionStatus.OPEN) {
                throw StockException.conflict("Market is not open: " + symbol);
            }
            return;
        }

        StockVirtualMarketConfig config = stockVirtualMarketConfigRepository.findById(symbol)
                .orElseThrow(() -> StockException.conflict("Market is not open: " + symbol));
        if (!Boolean.TRUE.equals(config.getEnabled()) || normalizeMarketSessionStatus(config.getMarketStatus()) != MarketSessionStatus.OPEN) {
            throw StockException.conflict("Market is not open: " + symbol);
        }
    }

    private MarketSessionStatus normalizeMarketSessionStatus(MarketSessionStatus marketStatus) {
        return marketStatus == null ? MarketSessionStatus.OPEN : marketStatus;
    }

    private void validateLimitPriceRule(String symbol, MarketType marketType, OrderType orderType, BigDecimal limitPrice) {
        if (orderType != OrderType.LIMIT || limitPrice == null) {
            return;
        }
        MarketPriceRule rule = resolveMarketPriceRule(symbol, marketType);
        if (limitPrice.remainder(rule.tickSize()).compareTo(BigDecimal.ZERO) != 0) {
            throw StockException.badRequest("Limit price must match tick size " + rule.tickSize().stripTrailingZeros().toPlainString());
        }

        BigDecimal lowerLimit = rule.basePrice()
                .multiply(ONE_HUNDRED.subtract(rule.priceLimitRate()))
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal upperLimit = rule.basePrice()
                .multiply(ONE_HUNDRED.add(rule.priceLimitRate()))
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        if (limitPrice.compareTo(lowerLimit) < 0 || limitPrice.compareTo(upperLimit) > 0) {
            throw StockException.badRequest(
                    "Limit price must be between " + lowerLimit.toPlainString() + " and " + upperLimit.toPlainString()
            );
        }
    }

    private MarketPriceRule resolveMarketPriceRule(String symbol, MarketType marketType) {
        if (marketType != MarketType.ORDER_BOOK) {
            StockPrice price = stockPriceRepository.findById(symbol)
                    .orElseThrow(() -> StockException.notFound("Price not found: " + symbol));
            return new MarketPriceRule(price.getPreviousClose(), DEFAULT_TICK_SIZE, DEFAULT_PRICE_LIMIT_RATE);
        }
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.findById(symbol)
                .orElseThrow(() -> StockException.notFound("Unknown stock symbol: " + symbol));
        BigDecimal basePrice = stockPriceRepository.findById(symbol)
                .map(StockPrice::getPreviousClose)
                .orElse(instrument.getInitialPrice());
        BigDecimal tickSize = instrument.getTickSize() == null ? DEFAULT_TICK_SIZE : instrument.getTickSize();
        BigDecimal priceLimitRate = instrument.getPriceLimitRate() == null ? DEFAULT_PRICE_LIMIT_RATE : instrument.getPriceLimitRate();
        return new MarketPriceRule(basePrice, tickSize, priceLimitRate);
    }

    private Optional<OrderResponse> findExistingClientOrder(Long accountId, String clientOrderId) {
        return stockOrderRepository.findByClientOrderId(clientOrderId)
                .map(order -> {
                    if (!order.getAccountId().equals(accountId)) {
                        throw StockException.conflict("Client order id already exists");
                    }
                    return toOrderResponse(order);
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
                    return toOrderResponse(order);
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

    private void amendBuyLimitOrder(String userKey, StockOrder order, long nextQuantity, BigDecimal nextLimitPrice) {
        long nextRemainingQuantity = nextQuantity - order.getFilledQuantity();
        BigDecimal nextReservedCash = nextLimitPrice.multiply(BigDecimal.valueOf(nextRemainingQuantity));
        BigDecimal reserveDiff = nextReservedCash.subtract(order.getReservedCash());
        StockAccount account = accountService.requireAccountForUpdate(userKey);
        if (reserveDiff.compareTo(BigDecimal.ZERO) > 0) {
            if (account.getCashBalance().compareTo(reserveDiff) < 0) {
                throw StockException.conflict("Not enough cash balance");
            }
            account.reserveCash(reserveDiff);
        } else if (reserveDiff.compareTo(BigDecimal.ZERO) < 0) {
            account.releaseCash(reserveDiff.abs());
        }
        order.amendLimitOrder(nextQuantity, nextLimitPrice, nextReservedCash);
    }

    private void amendSellLimitOrder(Long accountId, StockOrder order, long nextQuantity, BigDecimal nextLimitPrice) {
        long currentRemainingQuantity = order.getQuantity() - order.getFilledQuantity();
        long nextRemainingQuantity = nextQuantity - order.getFilledQuantity();
        long reserveDiff = nextRemainingQuantity - currentRemainingQuantity;
        StockHolding holding = stockHoldingRepository.findByAccountIdAndSymbolForUpdate(accountId, order.getSymbol())
                .orElseThrow(() -> StockException.conflict("Not enough holding quantity"));
        if (reserveDiff > 0) {
            if (holding.getAvailableQuantity() < reserveDiff) {
                throw StockException.conflict("Not enough holding quantity");
            }
            holding.reserveQuantity(reserveDiff);
        } else if (reserveDiff < 0) {
            holding.releaseReservedQuantity(Math.abs(reserveDiff));
        }
        order.amendLimitOrder(nextQuantity, nextLimitPrice, BigDecimal.ZERO);
    }

    private void releaseAllRemainingReservation(String userKey, Long accountId, StockOrder order) {
        if (order.getSide() == OrderSide.BUY && order.getReservedCash().compareTo(BigDecimal.ZERO) > 0) {
            accountService.requireAccountForUpdate(userKey).releaseCash(order.getReservedCash());
            return;
        }
        if (order.getSide() == OrderSide.SELL) {
            StockHolding holding = stockHoldingRepository.findByAccountIdAndSymbolForUpdate(accountId, order.getSymbol())
                    .orElseThrow(() -> StockException.conflict("Not enough holding quantity"));
            holding.releaseReservedQuantity(order.getQuantity() - order.getFilledQuantity());
        }
    }

    private BigDecimal calculateReservedCashForCancel(StockOrder order, long cancelQuantity, long remainingQuantity) {
        if (order.getOrderType() == OrderType.LIMIT && order.getLimitPrice() != null) {
            return order.getLimitPrice().multiply(BigDecimal.valueOf(cancelQuantity));
        }
        if (remainingQuantity == cancelQuantity) {
            return order.getReservedCash();
        }
        BigDecimal reservedPerShare = order.getReservedCash()
                .divide(BigDecimal.valueOf(remainingQuantity), 2, RoundingMode.HALF_UP);
        return reservedPerShare.multiply(BigDecimal.valueOf(cancelQuantity)).min(order.getReservedCash());
    }

    private BigDecimal calculateReservedCash(OrderRequest request, String symbol) {
        if (request.side() == OrderSide.SELL) {
            return BigDecimal.ZERO;
        }
        BigDecimal price = request.orderType() == OrderType.MARKET ? resolveReferencePrice(symbol) : request.limitPrice();
        return price.multiply(BigDecimal.valueOf(request.quantity()));
    }

    private record MarketPriceRule(
            BigDecimal basePrice,
            BigDecimal tickSize,
            BigDecimal priceLimitRate
    ) {
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
                order.getAccountId(),
                order.getClientOrderId(),
                order.getSymbol(),
                order.getMarketType(),
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
                execution.getAccountId(),
                execution.getOrderId(),
                execution.getSymbol(),
                execution.getSide(),
                execution.getQuantity(),
                execution.getPrice(),
                execution.getGrossAmount(),
                execution.getFeeAmount(),
                execution.getTaxAmount(),
                execution.getNetAmount(),
                execution.getRealizedProfit(),
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

    private ProfitSummaryResponse emptyProfitSummary() {
        return new ProfitSummaryResponse(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L
        );
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
