package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;

@Service
public class MarketRoleOrderCleanupService {

    private static final int ORDER_CHUNK_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public MarketRoleOrderCleanupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Transactional(
            transactionManager = "pubJdbcTransactionManager",
            propagation = Propagation.MANDATORY
    )
    public int cancelOpenOrderBookOrders(
            long accountId,
            String expectedParticipantCategory,
            String symbol,
            LocalDateTime cancelledAt
    ) {
        if (accountId <= 0L) {
            throw new IllegalArgumentException("Market-role account id must be positive");
        }
        String normalizedCategory = normalizeRequired(
                expectedParticipantCategory,
                "Market-role participant category"
        );
        String normalizedSymbol = symbol == null || symbol.isBlank()
                ? null
                : MarketTextNormalizer.symbol(symbol);
        if (cancelledAt == null) {
            throw new IllegalArgumentException("Market-role order cancellation time is required");
        }

        lockAndValidateAccount(accountId, normalizedCategory);
        List<OrderCandidate> candidates = findOpenOrderCandidates(accountId, normalizedSymbol);
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<String, Long> reservedSellQuantityBySymbol =
                lockSellHoldings(accountId, candidates);
        List<LockedOrder> openOrders = lockOpenOrders(candidates);
        if (openOrders.isEmpty()) {
            return 0;
        }
        requireNoAutoFundingBudget(openOrders);

        BigDecimal releasedBuyCash = openOrders.stream()
                .filter(order -> "BUY".equals(order.side()))
                .map(LockedOrder::normalizedReservedCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Long> releasedSellQuantityBySymbol = new LinkedHashMap<>();
        for (LockedOrder order : openOrders) {
            if ("SELL".equals(order.side()) && order.remainingQuantity() > 0L) {
                releasedSellQuantityBySymbol.merge(
                        order.symbol(),
                        order.remainingQuantity(),
                        Long::sum
                );
            }
        }
        validateSellReservations(
                reservedSellQuantityBySymbol,
                releasedSellQuantityBySymbol
        );
        cancelOrders(openOrders, cancelledAt);
        releaseBuyCash(accountId, releasedBuyCash, cancelledAt);
        releaseSellReservations(
                accountId,
                releasedSellQuantityBySymbol,
                cancelledAt
        );
        requireNoOpenOrder(accountId, normalizedSymbol);
        return openOrders.size();
    }

    private void lockAndValidateAccount(long accountId, String expectedCategory) {
        AccountState account = jdbcClient.sql(
                        """
                        select id, status, participant_category
                          from stock_account
                         where id = ?
                         for update
                        """
                )
                .param(accountId)
                .query((rs, rowNum) -> new AccountState(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("participant_category")
                ))
                .optional()
                .orElseThrow(() -> StockException.notFound(
                        "Market-role account not found: " + accountId
                ));
        if (!"ACTIVE".equals(account.status())
                || !expectedCategory.equals(account.participantCategory())) {
            throw StockException.conflict(
                    "Market-role account status or category is inconsistent: " + accountId
            );
        }
    }

    private List<OrderCandidate> findOpenOrderCandidates(
            long accountId,
            String symbol
    ) {
        if (symbol == null) {
            return jdbcClient.sql(
                            """
                            select id, side, symbol
                              from stock_order
                             where account_id = ?
                               and market_type = 'ORDER_BOOK'
                               and status in ('PENDING', 'PARTIALLY_FILLED')
                               and quantity > filled_quantity
                             order by id
                            """
                    )
                    .param(accountId)
                    .query((rs, rowNum) -> new OrderCandidate(
                            rs.getLong("id"),
                            rs.getString("side"),
                            rs.getString("symbol")
                    ))
                    .list();
        }
        return jdbcClient.sql(
                        """
                        select id, side, symbol
                          from stock_order
                         where account_id = ?
                           and symbol = ?
                           and market_type = 'ORDER_BOOK'
                           and status in ('PENDING', 'PARTIALLY_FILLED')
                           and quantity > filled_quantity
                         order by id
                        """
                )
                .param(accountId)
                .param(symbol)
                .query((rs, rowNum) -> new OrderCandidate(
                        rs.getLong("id"),
                        rs.getString("side"),
                        rs.getString("symbol")
                ))
                .list();
    }

    private Map<String, Long> lockSellHoldings(
            long accountId,
            List<OrderCandidate> candidates
    ) {
        List<String> sellSymbols = candidates.stream()
                .filter(candidate -> "SELL".equals(candidate.side()))
                .map(OrderCandidate::symbol)
                .distinct()
                .sorted()
                .toList();
        if (sellSymbols.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> reservedBySymbol = new LinkedHashMap<>();
        jdbcClient.sql(
                        """
                        select symbol, reserved_quantity
                          from stock_holding
                         where account_id = :accountId
                           and symbol in (:symbols)
                         order by symbol
                         for update
                        """
                )
                .param("accountId", accountId)
                .param("symbols", sellSymbols)
                .query((rs, rowNum) -> {
                    reservedBySymbol.put(
                            rs.getString("symbol"),
                            rs.getLong("reserved_quantity")
                    );
                    return 1;
                })
                .list();
        if (reservedBySymbol.size() != sellSymbols.size()) {
            throw StockException.conflict(
                    "Market-role sell holding is missing during order cancellation"
            );
        }
        return reservedBySymbol;
    }

    private List<LockedOrder> lockOpenOrders(List<OrderCandidate> candidates) {
        List<Long> orderIds = candidates.stream()
                .map(OrderCandidate::id)
                .distinct()
                .sorted()
                .toList();
        List<LockedOrder> lockedOrders = new ArrayList<>(orderIds.size());
        for (int offset = 0; offset < orderIds.size(); offset += ORDER_CHUNK_SIZE) {
            int end = Math.min(offset + ORDER_CHUNK_SIZE, orderIds.size());
            lockedOrders.addAll(
                    jdbcClient.sql(
                                    """
                                    select id, side, symbol, quantity,
                                           filled_quantity, reserved_cash
                                      from stock_order
                                     where id in (:orderIds)
                                       and status in ('PENDING', 'PARTIALLY_FILLED')
                                       and quantity > filled_quantity
                                     order by id
                                     for update
                                    """
                            )
                            .param("orderIds", orderIds.subList(offset, end))
                            .query((rs, rowNum) -> new LockedOrder(
                                    rs.getLong("id"),
                                    rs.getString("side"),
                                    rs.getString("symbol"),
                                    rs.getLong("quantity"),
                                    rs.getLong("filled_quantity"),
                                    rs.getBigDecimal("reserved_cash")
                            ))
                            .list()
            );
        }
        return lockedOrders;
    }

    private void requireNoAutoFundingBudget(List<LockedOrder> openOrders) {
        List<Long> orderIds = openOrders.stream()
                .map(LockedOrder::id)
                .toList();
        for (int offset = 0; offset < orderIds.size(); offset += ORDER_CHUNK_SIZE) {
            int end = Math.min(offset + ORDER_CHUNK_SIZE, orderIds.size());
            Long fundingBudgetLinks = jdbcClient.sql(
                            """
                            select count(*)
                              from stock_auto_participant_order_budget
                             where order_id in (:orderIds)
                               and remaining_reserved_amount > 0
                            """
                    )
                    .param("orderIds", orderIds.subList(offset, end))
                    .query(Long.class)
                    .single();
            if (fundingBudgetLinks != null && fundingBudgetLinks > 0L) {
                throw StockException.conflict(
                        "Market-role orders unexpectedly own auto-participant funding budgets"
                );
            }
        }
    }

    private void validateSellReservations(
            Map<String, Long> reservedBySymbol,
            Map<String, Long> releasedBySymbol
    ) {
        releasedBySymbol.forEach((symbol, releasedQuantity) -> {
            long reservedQuantity = reservedBySymbol.getOrDefault(symbol, -1L);
            if (reservedQuantity < releasedQuantity) {
                throw StockException.conflict(
                        "Market-role sell reservations exceed the locked holding: " + symbol
                );
            }
        });
    }

    private void cancelOrders(
            List<LockedOrder> openOrders,
            LocalDateTime cancelledAt
    ) {
        List<Long> orderIds = openOrders.stream()
                .map(LockedOrder::id)
                .toList();
        for (int offset = 0; offset < orderIds.size(); offset += ORDER_CHUNK_SIZE) {
            int end = Math.min(offset + ORDER_CHUNK_SIZE, orderIds.size());
            List<Long> chunk = orderIds.subList(offset, end);
            int cancelled = jdbcClient.sql(
                            """
                            update stock_order
                               set status = 'CANCELLED',
                                   reserved_cash = 0,
                                   updated_at = :cancelledAt
                             where id in (:orderIds)
                               and status in ('PENDING', 'PARTIALLY_FILLED')
                            """
                    )
                    .param("cancelledAt", cancelledAt)
                    .param("orderIds", chunk)
                    .update();
            if (cancelled != chunk.size()) {
                throw new IllegalStateException(
                        "Market-role order cancellation count mismatch: expected="
                                + chunk.size() + ", actual=" + cancelled
                );
            }
        }
    }

    private void releaseBuyCash(
            long accountId,
            BigDecimal releasedBuyCash,
            LocalDateTime cancelledAt
    ) {
        if (releasedBuyCash.signum() <= 0) {
            return;
        }
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_account
                           set cash_balance = cash_balance + ?,
                               updated_at = ?
                         where id = ?
                           and status = 'ACTIVE'
                        """,
                        releasedBuyCash,
                        cancelledAt,
                        accountId
                ),
                "Market-role buy-reservation release"
        );
    }

    private void releaseSellReservations(
            long accountId,
            Map<String, Long> releasedBySymbol,
            LocalDateTime cancelledAt
    ) {
        releasedBySymbol.forEach((symbol, releasedQuantity) -> requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_holding
                           set reserved_quantity = reserved_quantity - ?,
                               updated_at = ?
                         where account_id = ?
                           and symbol = ?
                           and reserved_quantity >= ?
                        """,
                        releasedQuantity,
                        cancelledAt,
                        accountId,
                        symbol,
                        releasedQuantity
                ),
                "Market-role sell-reservation release"
        ));
    }

    private void requireNoOpenOrder(long accountId, String symbol) {
        Long count;
        if (symbol == null) {
            count = jdbcClient.sql(
                            """
                            select count(*)
                              from stock_order
                             where account_id = ?
                               and market_type = 'ORDER_BOOK'
                               and status in ('PENDING', 'PARTIALLY_FILLED')
                               and quantity > filled_quantity
                            """
                    )
                    .param(accountId)
                    .query(Long.class)
                    .single();
        } else {
            count = jdbcClient.sql(
                            """
                            select count(*)
                              from stock_order
                             where account_id = ?
                               and symbol = ?
                               and market_type = 'ORDER_BOOK'
                               and status in ('PENDING', 'PARTIALLY_FILLED')
                               and quantity > filled_quantity
                            """
                    )
                    .param(accountId)
                    .param(symbol)
                    .query(Long.class)
                    .single();
        }
        if (count != null && count > 0L) {
            throw new IllegalStateException(
                    "Market-role open orders remain after exact cancellation"
            );
        }
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(
                    operation + " count mismatch: expected=1, actual=" + count
            );
        }
    }

    private record AccountState(
            long accountId,
            String status,
            String participantCategory
    ) {
    }

    private record OrderCandidate(
            long id,
            String side,
            String symbol
    ) {
    }

    private record LockedOrder(
            long id,
            String side,
            String symbol,
            long quantity,
            long filledQuantity,
            BigDecimal reservedCash
    ) {
        long remainingQuantity() {
            return Math.max(0L, quantity - filledQuantity);
        }

        BigDecimal normalizedReservedCash() {
            return reservedCash == null ? BigDecimal.ZERO : reservedCash;
        }
    }
}
