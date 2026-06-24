package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.StockOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface StockOrderRepository extends JpaRepository<StockOrder, Long> {
    List<StockOrder> findTop50ByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<StockOrder> findTop50ByAccountIdAndMarketTypeOrderByCreatedAtDesc(Long accountId, MarketType marketType);

    Optional<StockOrder> findByClientOrderId(String clientOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StockOrder o where o.id = :orderId")
    Optional<StockOrder> findByIdForUpdate(@Param("orderId") Long orderId);

    long countByAccountIdAndStatusIn(Long accountId, List<OrderStatus> statuses);

    @Query("""
            select count(o)
            from StockOrder o
            where o.accountId in (
                select a.id
                from StockAccount a
                join StockAutoParticipant p on p.userKey = a.userKey
                where p.enabled = true
                  and p.withdrawnAt is null
            )
              and o.status in :statuses
              and o.marketType = :marketType
            """)
    long countOpenAutoOrders(@Param("statuses") List<OrderStatus> statuses, @Param("marketType") MarketType marketType);

    long countByMarketTypeAndStatusIn(MarketType marketType, List<OrderStatus> statuses);

    @Query("""
            select coalesce(sum(o.reservedCash), 0)
            from StockOrder o
            where o.accountId = :accountId
              and o.side = :side
              and o.status in :statuses
            """)
    BigDecimal sumReservedCashByAccountIdAndSideAndStatusIn(
            @Param("accountId") Long accountId,
            @Param("side") OrderSide side,
            @Param("statuses") List<OrderStatus> statuses
    );

    @Query("""
            select o.limitPrice as price,
                   sum(o.quantity - o.filledQuantity) as quantity,
                   count(o.id) as orderCount
            from StockOrder o
            where o.symbol = :symbol
              and o.side = :side
              and o.marketType = :marketType
              and o.orderType = :orderType
              and o.status in :statuses
              and o.limitPrice is not null
              and o.quantity > o.filledQuantity
            group by o.limitPrice
            order by o.limitPrice desc
            """)
    List<OrderBookLevelView> findBidLevels(
            @Param("symbol") String symbol,
            @Param("marketType") MarketType marketType,
            @Param("side") OrderSide side,
            @Param("orderType") OrderType orderType,
            @Param("statuses") List<OrderStatus> statuses,
            Pageable pageable
    );

    @Query("""
            select o.limitPrice as price,
                   sum(o.quantity - o.filledQuantity) as quantity,
                   count(o.id) as orderCount
            from StockOrder o
            where o.symbol = :symbol
              and o.side = :side
              and o.marketType = :marketType
              and o.orderType = :orderType
              and o.status in :statuses
              and o.limitPrice is not null
              and o.quantity > o.filledQuantity
            group by o.limitPrice
            order by o.limitPrice asc
            """)
    List<OrderBookLevelView> findAskLevels(
            @Param("symbol") String symbol,
            @Param("marketType") MarketType marketType,
            @Param("side") OrderSide side,
            @Param("orderType") OrderType orderType,
            @Param("statuses") List<OrderStatus> statuses,
            Pageable pageable
    );

    interface OrderBookLevelView {
        BigDecimal getPrice();

        Long getQuantity();

        Long getOrderCount();
    }
}
