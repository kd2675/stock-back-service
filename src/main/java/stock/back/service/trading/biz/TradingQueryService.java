package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
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
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class TradingQueryService {

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
        Optional<StockAccount> account = accountService.findAccount(userKey);
        if (account.isEmpty()) {
            return Collections.emptyList();
        }
        List<StockOrder> orders = marketType == null
                ? stockOrderRepository.findTop50ByAccountIdOrderByCreatedAtDesc(account.get().getId())
                : stockOrderRepository.findTop50ByAccountIdAndMarketTypeOrderByCreatedAtDesc(
                        account.get().getId(),
                        marketType
                );
        return orders.stream()
                .map(TradingResponseMapper::toOrderResponse)
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
                : stockExecutionRepository.findTop50ByAccountIdAndSourceOrderByExecutedAtDesc(
                        account.get().getId(),
                        source
                );
        return executions.stream()
                .map(TradingResponseMapper::toExecutionResponse)
                .toList();
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
        BigDecimal marketValue = sum(holdings.stream()
                .map(HoldingResponse::marketValue));
        List<OrderStatus> activeOrderStatuses = activeOrderStatuses();
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
            return emptyProfitSummary();
        }
        StockAccount account = accountOptional.get();
        BigDecimal unrealizedProfit = sum(buildHoldingResponses(account.getId()).stream()
                .map(HoldingResponse::unrealizedProfit));
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

    @Transactional(readOnly = true)
    public FundFlowResponse getFundFlow(String userKey) {
        StockAccount account = accountService.requireAccount(userKey);
        List<HoldingResponse> holdings = buildHoldingResponses(account.getId());
        BigDecimal marketValue = sum(holdings.stream()
                .map(HoldingResponse::marketValue));
        BigDecimal unrealizedProfit = sum(holdings.stream()
                .map(HoldingResponse::unrealizedProfit));
        BigDecimal reservedBuyCash = stockOrderRepository.sumReservedCashByAccountIdAndSideAndStatusIn(
                account.getId(),
                OrderSide.BUY,
                activeOrderStatuses()
        );
        BigDecimal totalAsset = account.getCashBalance().add(reservedBuyCash).add(marketValue);
        StockAccountCashFlowRepository.CashFlowSummaryProjection cashFlowSummary =
                stockAccountCashFlowRepository.summarizeCashFlowsByAccountId(account.getId());
        StockExecutionRepository.ProfitSummaryProjection profitSummary =
                stockExecutionRepository.summarizeProfitByAccountId(account.getId());
        BigDecimal externalDepositAmount = zeroIfNull(cashFlowSummary.getExternalDepositAmount());
        BigDecimal externalWithdrawAmount = zeroIfNull(cashFlowSummary.getExternalWithdrawAmount());
        BigDecimal dividendIncomeAmount = zeroIfNull(cashFlowSummary.getDividendIncomeAmount());
        BigDecimal buyNetAmount = zeroIfNull(profitSummary.getBuyNetAmount());
        BigDecimal sellNetAmount = zeroIfNull(profitSummary.getSellNetAmount());
        BigDecimal realizedProfit = zeroIfNull(profitSummary.getRealizedProfit());
        List<AccountCashFlowResponse> recentCashFlows = stockAccountCashFlowRepository
                .findTop30ByAccountIdOrderByCreatedAtDescIdDesc(account.getId()).stream()
                .map(TradingResponseMapper::toAccountCashFlowResponse)
                .toList();

        return new FundFlowResponse(
                account.getCashBalance(),
                reservedBuyCash,
                marketValue,
                totalAsset,
                externalDepositAmount,
                externalWithdrawAmount,
                externalDepositAmount.subtract(externalWithdrawAmount),
                dividendIncomeAmount,
                buyNetAmount,
                sellNetAmount,
                sellNetAmount.subtract(buyNetAmount),
                zeroIfNull(profitSummary.getTotalFeeAmount()),
                zeroIfNull(profitSummary.getTotalTaxAmount()),
                realizedProfit,
                unrealizedProfit,
                realizedProfit.add(unrealizedProfit),
                profitSummary.getExecutionCount(),
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

    private List<OrderStatus> activeOrderStatuses() {
        return List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
    }

    private BigDecimal sum(Stream<BigDecimal> values) {
        return values.reduce(BigDecimal.ZERO, BigDecimal::add);
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
