package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockCorporateActionEntitlement;
import stock.back.service.database.entity.StockCorporateActionEntitlementStatus;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockCorporateActionEntitlementRepository extends JpaRepository<StockCorporateActionEntitlement, Long> {

    List<StockCorporateActionEntitlement> findTop50ByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<StockCorporateActionEntitlement> findByAccountIdAndStatusInOrderByCreatedAtDesc(
            Long accountId,
            Collection<StockCorporateActionEntitlementStatus> statuses
    );

    Optional<StockCorporateActionEntitlement> findByActionIdAndAccountId(Long actionId, Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from StockCorporateActionEntitlement e where e.actionId = :actionId and e.accountId = :accountId")
    Optional<StockCorporateActionEntitlement> findByActionIdAndAccountIdForUpdate(
            @Param("actionId") Long actionId,
            @Param("accountId") Long accountId
    );

    @Query("""
            select coalesce(sum(e.subscribedCashAmount), 0)
              from StockCorporateActionEntitlement e
             where e.accountId = :accountId
               and e.status = :status
            """)
    BigDecimal sumSubscribedCashAmountByAccountIdAndStatus(
            @Param("accountId") Long accountId,
            @Param("status") StockCorporateActionEntitlementStatus status
    );

    @Query("""
            select e.actionId as actionId,
                   coalesce(sum(e.subscribedShareQuantity), 0) as subscribedShareQuantity
              from StockCorporateActionEntitlement e
             where e.actionId in :actionIds
               and e.status in :statuses
             group by e.actionId
            """)
    List<SubscribedShareQuantitySummary> sumSubscribedShareQuantityByActionIdInAndStatusIn(
            @Param("actionIds") Collection<Long> actionIds,
            @Param("statuses") Collection<StockCorporateActionEntitlementStatus> statuses
    );

    interface SubscribedShareQuantitySummary {
        Long getActionId();

        Long getSubscribedShareQuantity();
    }
}
