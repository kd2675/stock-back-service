package stock.back.service.trading.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.repository.StockHoldingRepository;
import stock.back.service.market.biz.SimulationClockService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AccountOrderCleanupService {

    private static final int ORDER_CLEANUP_CHUNK_SIZE = 500;

    private final StockHoldingRepository stockHoldingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;
    private final AutoParticipantFundingBudgetReleaseService fundingBudgetReleaseService;

    public AccountOrderCleanupService(
            StockHoldingRepository stockHoldingRepository,
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService,
            AutoParticipantFundingBudgetReleaseService fundingBudgetReleaseService
    ) {
        this.stockHoldingRepository = stockHoldingRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationClockService = simulationClockService;
        this.fundingBudgetReleaseService = fundingBudgetReleaseService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOpenOrdersForDetach(StockAccount account) {
        cancelOpenOrders(account.getId(), account, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOpenOrderBookOrders(StockAccount account) {
        cancelOpenOrders(account.getId(), account, MarketType.ORDER_BOOK.name());
    }

    private void cancelOpenOrders(long accountId, StockAccount account, String marketType) {
        List<String> openStatuses = List.of(OrderStatus.PENDING.name(), OrderStatus.PARTIALLY_FILLED.name());
        LocalDateTime cancelledAt = simulationClockService.currentMarketDateTime();
        for (String openStatus : openStatuses) {
            OpenOrderCursor cursor = null;
            while (true) {
                List<OpenOrderReservation> candidates = findOpenOrderCandidates(
                        accountId,
                        openStatus,
                        marketType,
                        cursor
                );
                if (candidates.isEmpty()) {
                    break;
                }

                Map<String, StockHolding> sellHoldings = lockSellHoldingsForUpdate(accountId, candidates);
                List<OpenOrderReservation> openOrders = lockOpenOrderCandidatesForUpdate(candidates);
                cancelOpenOrdersInChunks(openOrders, openStatuses, cancelledAt);
                fundingBudgetReleaseService.releaseCancelledOrderBudgets(
                        openOrders.stream().map(OpenOrderReservation::id).toList(),
                        cancelledAt
                );
                releaseOpenBuyReservations(account, openOrders, cancelledAt);
                releaseOpenSellReservations(openOrders, sellHoldings, cancelledAt);

                OpenOrderReservation lastCandidate = candidates.getLast();
                cursor = new OpenOrderCursor(lastCandidate.createdAt(), lastCandidate.id());
            }
        }
    }

    private List<OpenOrderReservation> findOpenOrderCandidates(
            long accountId,
            String openStatus,
            String marketType,
            OpenOrderCursor cursor
    ) {
        String marketFilter = marketType == null ? "" : "and market_type = :marketType";
        String cursorFilter = cursor == null
                ? ""
                : "and (created_at > :cursorCreatedAt or (created_at = :cursorCreatedAt and id > :cursorId))";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                select id, side, symbol, status, quantity, filled_quantity, reserved_cash, created_at
                  from stock_order
                 where account_id = :accountId
                   %s
                   and status = :openStatus
                   %s
                 order by created_at asc, id asc
                 limit :chunkSize
                """.formatted(marketFilter, cursorFilter))
                .param("accountId", accountId)
                .param("openStatus", openStatus)
                .param("chunkSize", ORDER_CLEANUP_CHUNK_SIZE);
        if (marketType != null) {
            statement = statement.param("marketType", marketType);
        }
        if (cursor != null) {
            statement = statement
                    .param("cursorCreatedAt", cursor.createdAt())
                    .param("cursorId", cursor.id());
        }
        return statement
                .query((rs, rowNum) -> new OpenOrderReservation(
                        rs.getLong("id"),
                        rs.getString("side"),
                        rs.getString("symbol"),
                        rs.getString("status"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("reserved_cash"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ))
                .list();
    }

    private Map<String, StockHolding> lockSellHoldingsForUpdate(
            long accountId,
            List<OpenOrderReservation> candidates
    ) {
        List<String> symbols = candidates.stream()
                .filter(order -> OrderSide.SELL.name().equals(order.side()))
                .map(OpenOrderReservation::symbol)
                .distinct()
                .sorted()
                .toList();
        if (symbols.isEmpty()) {
            return Map.of();
        }
        return stockHoldingRepository.findByAccountIdAndSymbolsForUpdate(accountId, symbols).stream()
                .collect(Collectors.toMap(
                        StockHolding::getSymbol,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private List<OpenOrderReservation> lockOpenOrderCandidatesForUpdate(
            List<OpenOrderReservation> candidates
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> orderIds = candidates.stream()
                .map(OpenOrderReservation::id)
                .distinct()
                .sorted()
                .toList();
        List<OpenOrderReservation> lockedOrders = new ArrayList<>(orderIds.size());
        for (int offset = 0; offset < orderIds.size(); offset += ORDER_CLEANUP_CHUNK_SIZE) {
            int toIndex = Math.min(offset + ORDER_CLEANUP_CHUNK_SIZE, orderIds.size());
            lockedOrders.addAll(lockOpenOrderChunkForUpdate(orderIds.subList(offset, toIndex)));
        }
        return lockedOrders;
    }

    private List<OpenOrderReservation> lockOpenOrderChunkForUpdate(List<Long> orderIds) {
        return jdbcClient.sql(
                    """
                    select id, side, symbol, status, quantity, filled_quantity, reserved_cash
                      from stock_order
                     where id in (:orderIds)
                     order by id asc
                     for update
                    """
            )
            .param("orderIds", orderIds)
            .query((rs, rowNum) -> new OpenOrderReservation(
                    rs.getLong("id"),
                    rs.getString("side"),
                    rs.getString("symbol"),
                    rs.getString("status"),
                    rs.getLong("quantity"),
                    rs.getLong("filled_quantity"),
                    rs.getBigDecimal("reserved_cash"),
                    null
            ))
            .list()
            .stream()
            .filter(OpenOrderReservation::isOpen)
            .toList();
    }

    private void cancelOpenOrdersInChunks(
            List<OpenOrderReservation> openOrders,
            List<String> openStatuses,
            LocalDateTime cancelledAt
    ) {
        for (int offset = 0; offset < openOrders.size(); offset += ORDER_CLEANUP_CHUNK_SIZE) {
            int toIndex = Math.min(offset + ORDER_CLEANUP_CHUNK_SIZE, openOrders.size());
            List<Long> orderIds = openOrders.subList(offset, toIndex).stream()
                    .map(OpenOrderReservation::id)
                    .toList();
            int updated = jdbcClient.sql(
                            """
                            update stock_order
                               set status = 'CANCELLED',
                                   reserved_cash = 0,
                                   updated_at = :cancelledAt
                             where id in (:orderIds)
                               and status in (:openStatuses)
                            """
                    )
                    .param("cancelledAt", cancelledAt)
                    .param("orderIds", orderIds)
                    .param("openStatuses", openStatuses)
                    .update();
            if (updated != orderIds.size()) {
                throw new IllegalStateException(
                        "Open-order cleanup changed after exact PK lock: expected=" + orderIds.size()
                                + ", updated=" + updated
                );
            }
        }
    }

    private void releaseOpenBuyReservations(
            StockAccount account,
            List<OpenOrderReservation> cancelledOrders,
            LocalDateTime cancelledAt
    ) {
        BigDecimal reservedBuyCash = cancelledOrders.stream()
                .filter(order -> OrderSide.BUY.name().equals(order.side()))
                .map(OpenOrderReservation::reservedCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (reservedBuyCash.compareTo(BigDecimal.ZERO) > 0) {
            account.releaseCash(reservedBuyCash, cancelledAt);
        }
    }

    private void releaseOpenSellReservations(
            List<OpenOrderReservation> cancelledOrders,
            Map<String, StockHolding> sellHoldings,
            LocalDateTime cancelledAt
    ) {
        Map<String, Long> sellQuantityBySymbol = new TreeMap<>();
        for (OpenOrderReservation order : cancelledOrders) {
            if (OrderSide.SELL.name().equals(order.side()) && order.remainingQuantity() > 0) {
                sellQuantityBySymbol.merge(order.symbol(), order.remainingQuantity(), Long::sum);
            }
        }
        sellQuantityBySymbol.forEach((symbol, remainingQuantity) -> {
            StockHolding holding = sellHoldings.get(symbol);
            if (holding != null) {
                holding.releaseReservedQuantity(remainingQuantity, cancelledAt);
            }
        });
    }

    private record OpenOrderReservation(
            long id,
            String side,
            String symbol,
            String status,
            long quantity,
            long filledQuantity,
            BigDecimal reservedCash,
            LocalDateTime createdAt
    ) {

        long remainingQuantity() {
            return quantity - filledQuantity;
        }

        boolean isOpen() {
            return remainingQuantity() > 0
                    && (OrderStatus.PENDING.name().equals(status)
                    || OrderStatus.PARTIALLY_FILLED.name().equals(status));
        }
    }

    private record OpenOrderCursor(LocalDateTime createdAt, long id) {
    }
}
