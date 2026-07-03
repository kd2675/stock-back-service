package stock.back.service.trading.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.market.biz.SimulationClockService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class AccountOrderCleanupService {

    private final StockHoldingRepository stockHoldingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;

    public AccountOrderCleanupService(
            StockHoldingRepository stockHoldingRepository,
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService
    ) {
        this.stockHoldingRepository = stockHoldingRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationClockService = simulationClockService;
    }

    public void cancelOpenOrdersForDetach(StockAccount account) {
        cancelOpenOrders(account.getId(), account, null);
    }

    public void cancelOpenOrderBookOrders(StockAccount account) {
        cancelOpenOrders(account.getId(), account, MarketType.ORDER_BOOK.name());
    }

    public void cancelOpenOrderBookOrders(long accountId) {
        cancelOpenOrders(accountId, null, MarketType.ORDER_BOOK.name());
    }

    private void cancelOpenOrders(long accountId, StockAccount account, String marketType) {
        List<String> openStatuses = List.of(OrderStatus.PENDING.name(), OrderStatus.PARTIALLY_FILLED.name());
        LocalDateTime cancelledAt = simulationClockService.currentMarketDateTime();
        List<OpenOrderReservation> openOrders = findOpenOrdersForUpdate(accountId, openStatuses, marketType);
        List<OpenOrderReservation> cancelledOrders = new ArrayList<>();
        for (OpenOrderReservation order : openOrders) {
            if (cancelOpenOrder(order.id(), openStatuses, cancelledAt)) {
                cancelledOrders.add(order);
            }
        }
        releaseOpenBuyReservations(accountId, account, cancelledOrders, cancelledAt);
        releaseOpenSellReservations(accountId, cancelledOrders, cancelledAt);
    }

    private List<OpenOrderReservation> findOpenOrdersForUpdate(long accountId, List<String> openStatuses, String marketType) {
        return jdbcClient.sql(
                marketType == null
                        ? """
                        select id, side, symbol, quantity, filled_quantity, reserved_cash
                          from stock_order
                         where account_id = ?
                           and status in (?, ?)
                         order by id asc
                         for update
                        """
                        : """
                        select id, side, symbol, quantity, filled_quantity, reserved_cash
                          from stock_order
                         where account_id = ?
                           and market_type = ?
                           and status in (?, ?)
                         order by id asc
                         for update
                        """
        )
                .params(marketType == null
                        ? List.of(accountId, openStatuses.get(0), openStatuses.get(1))
                        : List.of(accountId, marketType, openStatuses.get(0), openStatuses.get(1)))
                .query((rs, rowNum) -> new OpenOrderReservation(
                        rs.getLong("id"),
                        rs.getString("side"),
                        rs.getString("symbol"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ))
                .list();
    }

    private boolean cancelOpenOrder(long orderId, List<String> openStatuses, LocalDateTime cancelledAt) {
        return jdbcTemplate.update(
                """
                update stock_order
                   set status = 'CANCELLED',
                       reserved_cash = 0,
                       updated_at = ?
                 where id = ?
                   and status in (?, ?)
                """,
                cancelledAt,
                orderId,
                openStatuses.get(0),
                openStatuses.get(1)
        ) > 0;
    }

    private void releaseOpenBuyReservations(
            long accountId,
            StockAccount account,
            List<OpenOrderReservation> cancelledOrders,
            LocalDateTime cancelledAt
    ) {
        BigDecimal reservedBuyCash = cancelledOrders.stream()
                .filter(order -> OrderSide.BUY.name().equals(order.side()))
                .map(OpenOrderReservation::reservedCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (reservedBuyCash.compareTo(BigDecimal.ZERO) > 0) {
            if (account == null) {
                jdbcTemplate.update(
                        "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where id = ?",
                        reservedBuyCash,
                        cancelledAt,
                        accountId
                );
            } else {
                account.releaseCash(reservedBuyCash, cancelledAt);
            }
        }
    }

    private void releaseOpenSellReservations(
            long accountId,
            List<OpenOrderReservation> cancelledOrders,
            LocalDateTime cancelledAt
    ) {
        Map<String, Long> sellQuantityBySymbol = new TreeMap<>();
        for (OpenOrderReservation order : cancelledOrders) {
            if (OrderSide.SELL.name().equals(order.side()) && order.remainingQuantity() > 0) {
                sellQuantityBySymbol.merge(order.symbol(), order.remainingQuantity(), Long::sum);
            }
        }
        sellQuantityBySymbol.forEach((symbol, remainingQuantity) ->
                stockHoldingRepository.findByAccountIdAndSymbolForUpdate(accountId, symbol)
                    .ifPresent(holding -> holding.releaseReservedQuantity(remainingQuantity, cancelledAt)));
    }

    private record OpenOrderReservation(
            long id,
            String side,
            String symbol,
            long quantity,
            long filledQuantity,
            BigDecimal reservedCash
    ) {

        long remainingQuantity() {
            return quantity - filledQuantity;
        }
    }
}
