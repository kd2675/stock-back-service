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
import java.util.List;

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
        cancelOpenOrders(account, null);
    }

    public void cancelOpenOrderBookOrders(StockAccount account) {
        cancelOpenOrders(account, MarketType.ORDER_BOOK.name());
    }

    private void cancelOpenOrders(StockAccount account, String marketType) {
        List<String> openStatuses = List.of(OrderStatus.PENDING.name(), OrderStatus.PARTIALLY_FILLED.name());
        LocalDateTime cancelledAt = simulationClockService.currentMarketDateTime();
        releaseOpenBuyReservations(account, openStatuses, marketType, cancelledAt);
        releaseOpenSellReservations(account, openStatuses, marketType, cancelledAt);
        cancelOpenOrders(account, openStatuses, marketType, cancelledAt);
    }

    private void releaseOpenBuyReservations(StockAccount account, List<String> openStatuses, String marketType, LocalDateTime cancelledAt) {
        BigDecimal reservedBuyCash = jdbcClient.sql(
                marketType == null
                        ? """
                        select coalesce(sum(reserved_cash), 0)
                          from stock_order
                         where account_id = ?
                           and side = ?
                           and status in (?, ?)
                        """
                        : """
                        select coalesce(sum(reserved_cash), 0)
                          from stock_order
                         where account_id = ?
                           and market_type = ?
                           and side = ?
                           and status in (?, ?)
                        """
        )
                .params(marketType == null
                        ? List.of(account.getId(), OrderSide.BUY.name(), openStatuses.get(0), openStatuses.get(1))
                        : List.of(account.getId(), marketType, OrderSide.BUY.name(), openStatuses.get(0), openStatuses.get(1)))
                .query(BigDecimal.class)
                .single();
        if (reservedBuyCash.compareTo(BigDecimal.ZERO) > 0) {
            account.releaseCash(reservedBuyCash, cancelledAt);
        }
    }

    private void releaseOpenSellReservations(StockAccount account, List<String> openStatuses, String marketType, LocalDateTime cancelledAt) {
        List<SellReservation> sellReservations = jdbcClient.sql(
                marketType == null
                        ? """
                        select symbol, coalesce(sum(quantity - filled_quantity), 0) as remaining_quantity
                          from stock_order
                         where account_id = ?
                           and side = ?
                           and status in (?, ?)
                         group by symbol
                        """
                        : """
                        select symbol, coalesce(sum(quantity - filled_quantity), 0) as remaining_quantity
                          from stock_order
                         where account_id = ?
                           and market_type = ?
                           and side = ?
                           and status in (?, ?)
                         group by symbol
                        """
        )
                .params(marketType == null
                        ? List.of(account.getId(), OrderSide.SELL.name(), openStatuses.get(0), openStatuses.get(1))
                        : List.of(account.getId(), marketType, OrderSide.SELL.name(), openStatuses.get(0), openStatuses.get(1)))
                .query((rs, rowNum) -> new SellReservation(
                        rs.getString("symbol"),
                        rs.getLong("remaining_quantity")
                ))
                .list();
        for (SellReservation reservation : sellReservations) {
            stockHoldingRepository.findByAccountIdAndSymbolForUpdate(account.getId(), reservation.symbol())
                    .ifPresent(holding -> holding.releaseReservedQuantity(reservation.remainingQuantity(), cancelledAt));
        }
    }

    private void cancelOpenOrders(StockAccount account, List<String> openStatuses, String marketType, LocalDateTime cancelledAt) {
        if (marketType == null) {
            jdbcTemplate.update(
                    """
                    update stock_order
                       set status = 'CANCELLED',
                           reserved_cash = 0,
                           updated_at = ?
                     where account_id = ?
                       and status in (?, ?)
                    """,
                    cancelledAt,
                    account.getId(),
                    openStatuses.get(0),
                    openStatuses.get(1)
            );
            return;
        }
        jdbcTemplate.update(
                """
                update stock_order
                   set status = 'CANCELLED',
                       reserved_cash = 0,
                       updated_at = ?
                 where account_id = ?
                   and market_type = ?
                   and status in (?, ?)
                """,
                cancelledAt,
                account.getId(),
                marketType,
                openStatuses.get(0),
                openStatuses.get(1)
        );
    }

    private record SellReservation(String symbol, long remainingQuantity) {
    }
}
