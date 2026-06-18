package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.StockOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface StockOrderRepository extends JpaRepository<StockOrder, Long> {
    List<StockOrder> findTop50ByUserKeyOrderByCreatedAtDesc(String userKey);

    Optional<StockOrder> findByClientOrderId(String clientOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StockOrder o where o.id = :orderId")
    Optional<StockOrder> findByIdForUpdate(@Param("orderId") Long orderId);

    long countByUserKeyAndStatusIn(String userKey, List<OrderStatus> statuses);

    @Query("""
            select coalesce(sum(o.reservedCash), 0)
            from StockOrder o
            where o.userKey = :userKey
              and o.side = :side
              and o.status in :statuses
            """)
    BigDecimal sumReservedCashByUserKeyAndSideAndStatusIn(
            @Param("userKey") String userKey,
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
              and o.orderType = :orderType
              and o.status in :statuses
              and o.limitPrice is not null
              and o.quantity > o.filledQuantity
            group by o.limitPrice
            order by o.limitPrice desc
            """)
    List<OrderBookLevelView> findBidLevels(
            @Param("symbol") String symbol,
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
              and o.orderType = :orderType
              and o.status in :statuses
              and o.limitPrice is not null
              and o.quantity > o.filledQuantity
            group by o.limitPrice
            order by o.limitPrice asc
            """)
    List<OrderBookLevelView> findAskLevels(
            @Param("symbol") String symbol,
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
