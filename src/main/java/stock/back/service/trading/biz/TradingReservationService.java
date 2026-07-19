package stock.back.service.trading.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.entity.StockOrder;
import stock.back.service.database.repository.StockHoldingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TradingReservationService {

    private final StockHoldingRepository stockHoldingRepository;

    void reserveBuyOrder(StockAccount account, BigDecimal reservedCash, LocalDateTime reservedAt) {
        if (account.getCashBalance().compareTo(reservedCash) < 0) {
            throw StockException.conflict("Not enough cash balance");
        }
        account.reserveCash(reservedCash, reservedAt);
    }

    StockHolding findSellHoldingForUpdate(Long accountId, String symbol) {
        return stockHoldingRepository.findByAccountIdAndSymbolForUpdate(accountId, symbol)
                .orElseThrow(() -> StockException.conflict("Not enough holding quantity"));
    }

    void reserveSellOrder(StockHolding holding, long quantity, LocalDateTime reservedAt) {
        if (holding.getAvailableQuantity() < quantity) {
            throw StockException.conflict("Not enough holding quantity");
        }
        holding.reserveQuantity(quantity, reservedAt);
    }

    void releaseOnCancel(
            StockAccount account,
            StockHolding sellHolding,
            StockOrder order,
            LocalDateTime cancelledAt
    ) {
        if (order.getSide() == OrderSide.BUY && order.getReservedCash().compareTo(BigDecimal.ZERO) > 0) {
            account.releaseCash(order.getReservedCash(), cancelledAt);
        }
        if (order.getSide() == OrderSide.SELL && sellHolding != null) {
            sellHolding.releaseReservedQuantity(order.getQuantity() - order.getFilledQuantity(), cancelledAt);
        }
    }

    void amendBuyLimitOrder(
            StockAccount account,
            StockOrder order,
            long nextQuantity,
            BigDecimal nextLimitPrice,
            LocalDateTime amendedAt
    ) {
        long nextRemainingQuantity = nextQuantity - order.getFilledQuantity();
        BigDecimal nextReservedCash = nextLimitPrice.multiply(BigDecimal.valueOf(nextRemainingQuantity));
        BigDecimal reserveDiff = nextReservedCash.subtract(order.getReservedCash());
        if (reserveDiff.compareTo(BigDecimal.ZERO) > 0) {
            reserveBuyOrder(account, reserveDiff, amendedAt);
        } else if (reserveDiff.compareTo(BigDecimal.ZERO) < 0) {
            account.releaseCash(reserveDiff.abs(), amendedAt);
        }
        order.amendLimitOrder(nextQuantity, nextLimitPrice, nextReservedCash, amendedAt);
    }

    void amendSellLimitOrder(
            StockHolding holding,
            StockOrder order,
            long nextQuantity,
            BigDecimal nextLimitPrice,
            LocalDateTime amendedAt
    ) {
        long currentRemainingQuantity = order.getQuantity() - order.getFilledQuantity();
        long nextRemainingQuantity = nextQuantity - order.getFilledQuantity();
        long reserveDiff = nextRemainingQuantity - currentRemainingQuantity;
        if (reserveDiff > 0) {
            reserveSellOrder(holding, reserveDiff, amendedAt);
        } else if (reserveDiff < 0) {
            holding.releaseReservedQuantity(Math.abs(reserveDiff), amendedAt);
        }
        order.amendLimitOrder(nextQuantity, nextLimitPrice, BigDecimal.ZERO, amendedAt);
    }

    void releaseAllRemainingReservation(
            StockAccount account,
            StockHolding sellHolding,
            StockOrder order,
            LocalDateTime cancelledAt
    ) {
        if (order.getSide() == OrderSide.BUY && order.getReservedCash().compareTo(BigDecimal.ZERO) > 0) {
            account.releaseCash(order.getReservedCash(), cancelledAt);
            return;
        }
        if (order.getSide() == OrderSide.SELL && sellHolding != null) {
            sellHolding.releaseReservedQuantity(order.getQuantity() - order.getFilledQuantity(), cancelledAt);
        }
    }

    void releasePartialReservation(
            StockAccount account,
            StockHolding sellHolding,
            StockOrder order,
            long cancelQuantity,
            long remainingQuantity,
            LocalDateTime cancelledAt
    ) {
        if (order.getSide() == OrderSide.BUY) {
            BigDecimal release = calculateReservedCashForCancel(order, cancelQuantity, remainingQuantity);
            account.releaseCash(release, cancelledAt);
            order.reduceOpenQuantity(
                    cancelQuantity,
                    order.getReservedCash().subtract(release).max(BigDecimal.ZERO),
                    cancelledAt
            );
            return;
        }

        if (sellHolding == null) {
            throw StockException.conflict("Not enough holding quantity");
        }
        sellHolding.releaseReservedQuantity(cancelQuantity, cancelledAt);
        order.reduceOpenQuantity(cancelQuantity, BigDecimal.ZERO, cancelledAt);
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
}
