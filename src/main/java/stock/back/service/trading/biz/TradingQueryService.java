package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockExecution;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.entity.StockOrder;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockExecutionRepository;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.trading.vo.AccountCashFlowResponse;
import stock.back.service.trading.vo.ExecutionResponse;
import stock.back.service.trading.vo.FundFlowResponse;
import stock.back.service.trading.vo.HoldingResponse;
import stock.back.service.trading.vo.OrderResponse;
import stock.back.service.trading.vo.PortfolioResponse;
import stock.back.service.trading.vo.PortfolioSnapshotResponse;
import stock.back.service.trading.vo.ProfitSummaryResponse;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TradingQueryService {

    private static final int DEFAULT_ACTIVITY_LIMIT = 50;
    private static final int MAX_ACTIVITY_LIMIT = 50;

    private static final List<OrderStatus> ACTIVE_ORDER_STATUSES = List.of(
            OrderStatus.PENDING,
            OrderStatus.PARTIALLY_FILLED
    );

    private final AccountService accountService;
    private final StockOrderRepository stockOrderRepository;
    private final StockHoldingRepository stockHoldingRepository;
    private final StockExecutionRepository stockExecutionRepository;
    private final StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockPriceCacheService stockPriceCacheService;

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrders(String userKey, MarketType marketType) {
        return getOrders(userKey, marketType, null, DEFAULT_ACTIVITY_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrders(String userKey, MarketType marketType, String symbol, Integer limit) {
        String normalizedSymbol = normalizeSymbol(symbol);
        int normalizedLimit = normalizeLimit(limit);
        return accountService.findAccount(userKey)
                .map(account -> findOrders(account.getId(), marketType, normalizedSymbol, normalizedLimit).stream()
                        .map(TradingResponseMapper::toOrderResponse)
                        .toList())
                .orElseGet(Collections::emptyList);
    }

    private List<StockOrder> findOrders(Long accountId, MarketType marketType, String symbol, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        if (marketType == null && symbol == null) {
            return stockOrderRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable);
        }
        if (marketType == null) {
            return stockOrderRepository.findByAccountIdAndSymbolOrderByCreatedAtDesc(accountId, symbol, pageable);
        }
        if (symbol == null) {
            return stockOrderRepository.findByAccountIdAndMarketTypeOrderByCreatedAtDesc(accountId, marketType, pageable);
        }
        return stockOrderRepository.findByAccountIdAndMarketTypeAndSymbolOrderByCreatedAtDesc(accountId, marketType, symbol, pageable);
    }

    @Transactional(readOnly = true)
    public List<ExecutionResponse> getExecutions(String userKey, ExecutionSource source) {
        return getExecutions(userKey, source, null, DEFAULT_ACTIVITY_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<ExecutionResponse> getExecutions(String userKey, ExecutionSource source, String symbol, Integer limit) {
        String normalizedSymbol = normalizeSymbol(symbol);
        int normalizedLimit = normalizeLimit(limit);
        return accountService.findAccount(userKey)
                .map(account -> findExecutions(account.getId(), source, normalizedSymbol, normalizedLimit).stream()
                        .map(TradingResponseMapper::toExecutionResponse)
                        .toList())
                .orElseGet(Collections::emptyList);
    }

    private List<StockExecution> findExecutions(Long accountId, ExecutionSource source, String symbol, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        if (source == null && symbol == null) {
            return stockExecutionRepository.findByAccountIdOrderByExecutedAtDesc(accountId, pageable);
        }
        if (source == null) {
            return stockExecutionRepository.findByAccountIdAndSymbolOrderByExecutedAtDesc(accountId, symbol, pageable);
        }
        if (symbol == null) {
            return stockExecutionRepository.findByAccountIdAndSourceOrderByExecutedAtDesc(accountId, source, pageable);
        }
        return stockExecutionRepository.findByAccountIdAndSourceAndSymbolOrderByExecutedAtDesc(accountId, source, symbol, pageable);
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> getHoldings(String userKey) {
        return accountService.findAccount(userKey)
                .map(account -> buildHoldingResponses(account.getId()))
                .orElseGet(Collections::emptyList);
    }

    @Transactional(readOnly = true)
    public PortfolioResponse getPortfolio(String userKey) {
        StockAccount account = accountService.requireAccount(userKey);
        List<HoldingResponse> holdings = buildHoldingResponses(account.getId());
        BigDecimal reservedBuyCash = stockOrderRepository.sumReservedCashByAccountIdAndSideAndStatusIn(
                account.getId(),
                OrderSide.BUY,
                ACTIVE_ORDER_STATUSES
        );
        BigDecimal netCashFlow = accountService.getNetCashFlow(account.getId());
        long pendingCount = stockOrderRepository.countByAccountIdAndStatusIn(
                account.getId(),
                ACTIVE_ORDER_STATUSES
        );
        return TradingResponseMapper.toPortfolioResponse(
                accountService.toResponse(account),
                account.getCashBalance(),
                holdings,
                reservedBuyCash,
                netCashFlow,
                pendingCount
        );
    }

    @Transactional(readOnly = true)
    public List<PortfolioSnapshotResponse> getPortfolioSnapshots(String userKey) {
        return accountService.findAccount(userKey)
                .map(account -> portfolioSnapshotRepository.findTop30ByAccountIdOrderBySnapshotDateDesc(account.getId())
                        .stream()
                        .map(TradingResponseMapper::toPortfolioSnapshotResponse)
                        .toList())
                .orElseGet(Collections::emptyList);
    }

    @Transactional(readOnly = true)
    public ProfitSummaryResponse getProfitSummary(String userKey) {
        Optional<StockAccount> accountOptional = accountService.findAccount(userKey);
        if (accountOptional.isEmpty()) {
            return TradingResponseMapper.emptyProfitSummary();
        }
        StockAccount account = accountOptional.get();
        BigDecimal unrealizedProfit = buildHoldingResponses(account.getId()).stream()
                .map(HoldingResponse::unrealizedProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        StockExecutionRepository.ProfitSummaryProjection summary =
                stockExecutionRepository.summarizeProfitByAccountId(account.getId());
        return TradingResponseMapper.toProfitSummary(summary, unrealizedProfit);
    }

    @Transactional(readOnly = true)
    public FundFlowResponse getFundFlow(String userKey) {
        StockAccount account = accountService.requireAccount(userKey);
        List<HoldingResponse> holdings = buildHoldingResponses(account.getId());
        BigDecimal reservedBuyCash = stockOrderRepository.sumReservedCashByAccountIdAndSideAndStatusIn(
                account.getId(),
                OrderSide.BUY,
                ACTIVE_ORDER_STATUSES
        );
        StockAccountCashFlowRepository.CashFlowSummaryProjection cashFlowSummary =
                stockAccountCashFlowRepository.summarizeCashFlowsByAccountId(account.getId());
        StockExecutionRepository.ProfitSummaryProjection profitSummary =
                stockExecutionRepository.summarizeProfitByAccountId(account.getId());
        List<AccountCashFlowResponse> recentCashFlows = stockAccountCashFlowRepository
                .findTop30ByAccountIdOrderByCreatedAtDescIdDesc(account.getId()).stream()
                .map(TradingResponseMapper::toAccountCashFlowResponse)
                .toList();
        return TradingResponseMapper.toFundFlow(
                account,
                holdings,
                reservedBuyCash,
                cashFlowSummary,
                profitSummary,
                recentCashFlows
        );
    }

    private List<HoldingResponse> buildHoldingResponses(Long accountId) {
        List<StockHolding> holdings = stockHoldingRepository.findByAccountIdOrderBySymbolAsc(accountId);
        List<String> holdingSymbols = holdings.stream()
                .map(StockHolding::getSymbol)
                .distinct()
                .toList();
        Map<String, BigDecimal> cachedPricesBySymbol = stockPriceCacheService.getCachedPrices(holdingSymbols)
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().currentPrice()));
        List<String> uncachedSymbols = holdingSymbols.stream()
                .filter(symbol -> !cachedPricesBySymbol.containsKey(symbol))
                .toList();
        Map<String, StockPrice> pricesBySymbol = uncachedSymbols.isEmpty()
                ? Map.of()
                : stockPriceRepository.findAllById(uncachedSymbols)
                        .stream()
                        .collect(Collectors.toMap(StockPrice::getSymbol, Function.identity()));
        return holdings.stream()
                .map(holding -> toHoldingResponse(holding, cachedPricesBySymbol, pricesBySymbol))
                .toList();
    }

    private HoldingResponse toHoldingResponse(
            StockHolding holding,
            Map<String, BigDecimal> cachedPricesBySymbol,
            Map<String, StockPrice> pricesBySymbol
    ) {
        BigDecimal currentPrice = Optional.ofNullable(cachedPricesBySymbol.get(holding.getSymbol()))
                .or(() -> Optional.ofNullable(pricesBySymbol.get(holding.getSymbol())).map(StockPrice::getCurrentPrice))
                .orElse(holding.getAveragePrice());
        return TradingResponseMapper.toHoldingResponse(holding, currentPrice);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_ACTIVITY_LIMIT;
        }
        return Math.min(Math.max(limit, 1), MAX_ACTIVITY_LIMIT);
    }

}
