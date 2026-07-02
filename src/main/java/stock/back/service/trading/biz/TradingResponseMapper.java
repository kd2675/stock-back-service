package stock.back.service.trading.biz;

import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockExecution;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.entity.StockOrder;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockExecutionRepository;
import stock.back.service.trading.vo.AccountCashFlowResponse;
import stock.back.service.trading.vo.AccountResponse;
import stock.back.service.trading.vo.ExecutionResponse;
import stock.back.service.trading.vo.FundFlowResponse;
import stock.back.service.trading.vo.HoldingResponse;
import stock.back.service.trading.vo.OrderResponse;
import stock.back.service.trading.vo.PortfolioResponse;
import stock.back.service.trading.vo.PortfolioSnapshotResponse;
import stock.back.service.trading.vo.ProfitSummaryResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Stream;

final class TradingResponseMapper {

    private TradingResponseMapper() {
    }

    static OrderResponse toOrderResponse(StockOrder order) {
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

    static ExecutionResponse toExecutionResponse(StockExecution execution) {
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

    static HoldingResponse toHoldingResponse(StockHolding holding, BigDecimal currentPrice) {
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

    static PortfolioSnapshotResponse toPortfolioSnapshotResponse(PortfolioSnapshot snapshot) {
        return new PortfolioSnapshotResponse(
                snapshot.getSnapshotDate(),
                snapshot.getTotalAsset(),
                snapshot.getCashBalance(),
                snapshot.getMarketValue(),
                snapshot.getReturnRate()
        );
    }

    static AccountCashFlowResponse toAccountCashFlowResponse(StockAccountCashFlow cashFlow) {
        return new AccountCashFlowResponse(
                cashFlow.getId(),
                cashFlow.getFlowType().name(),
                cashFlow.getAmount(),
                cashFlow.getReason().name(),
                cashFlow.getCreatedBy(),
                cashFlow.getCreatedAt()
        );
    }

    static PortfolioResponse toPortfolioResponse(
            AccountResponse account,
            BigDecimal cashBalance,
            List<HoldingResponse> holdings,
            BigDecimal reservedBuyCash,
            BigDecimal netCashFlow,
            long pendingCount
    ) {
        BigDecimal marketValue = sum(holdings.stream().map(HoldingResponse::marketValue));
        BigDecimal totalAsset = cashBalance.add(reservedBuyCash).add(marketValue);
        return new PortfolioResponse(
                account,
                marketValue,
                reservedBuyCash,
                totalAsset,
                returnRate(totalAsset, netCashFlow),
                pendingCount,
                holdings
        );
    }

    static ProfitSummaryResponse toProfitSummary(
            StockExecutionRepository.ProfitSummaryProjection summary,
            BigDecimal unrealizedProfit
    ) {
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

    static ProfitSummaryResponse emptyProfitSummary() {
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

    static FundFlowResponse toFundFlow(
            StockAccount account,
            List<HoldingResponse> holdings,
            BigDecimal reservedBuyCash,
            StockAccountCashFlowRepository.CashFlowSummaryProjection cashFlowSummary,
            StockExecutionRepository.ProfitSummaryProjection profitSummary,
            List<AccountCashFlowResponse> recentCashFlows
    ) {
        BigDecimal marketValue = sum(holdings.stream().map(HoldingResponse::marketValue));
        BigDecimal unrealizedProfit = sum(holdings.stream().map(HoldingResponse::unrealizedProfit));
        BigDecimal totalAsset = account.getCashBalance().add(reservedBuyCash).add(marketValue);
        BigDecimal externalDepositAmount = zeroIfNull(cashFlowSummary.getExternalDepositAmount());
        BigDecimal externalWithdrawAmount = zeroIfNull(cashFlowSummary.getExternalWithdrawAmount());
        BigDecimal dividendIncomeAmount = zeroIfNull(cashFlowSummary.getDividendIncomeAmount());
        BigDecimal buyNetAmount = zeroIfNull(profitSummary.getBuyNetAmount());
        BigDecimal sellNetAmount = zeroIfNull(profitSummary.getSellNetAmount());
        BigDecimal realizedProfit = zeroIfNull(profitSummary.getRealizedProfit());
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

    private static BigDecimal returnRate(BigDecimal totalAsset, BigDecimal netCashFlow) {
        if (netCashFlow.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return totalAsset.subtract(netCashFlow)
                .multiply(BigDecimal.valueOf(100))
                .divide(netCashFlow, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(Stream<BigDecimal> values) {
        return values.reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
