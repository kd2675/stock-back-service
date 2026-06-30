package stock.back.service.trading.biz;

import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockExecution;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.entity.StockOrder;
import stock.back.service.trading.vo.AccountCashFlowResponse;
import stock.back.service.trading.vo.ExecutionResponse;
import stock.back.service.trading.vo.HoldingResponse;
import stock.back.service.trading.vo.OrderResponse;
import stock.back.service.trading.vo.PortfolioSnapshotResponse;

import java.math.BigDecimal;

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
}
