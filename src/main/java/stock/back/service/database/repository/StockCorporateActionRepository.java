package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockCorporateActionRepository extends JpaRepository<StockCorporateAction, Long> {

    List<StockCorporateAction> findBySymbolOrderByCreatedAtDesc(String symbol);

    List<StockCorporateAction> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    List<StockCorporateAction> findByActionTypeOrderByCreatedAtDescIdDesc(
            StockCorporateActionType actionType,
            Pageable pageable
    );

    List<StockCorporateAction> findByActionTypeAndStatusInOrderByCreatedAtDescIdDesc(
            StockCorporateActionType actionType,
            Collection<StockCorporateActionStatus> statuses
    );

    boolean existsBySymbolAndActionTypeInAndStatusIn(
            String symbol,
            Collection<StockCorporateActionType> actionTypes,
            Collection<StockCorporateActionStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockCorporateAction a where a.id = :id")
    Optional<StockCorporateAction> findByIdForUpdate(@Param("id") Long id);
}
