package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockCorporateAction;

import java.util.List;
import java.util.Optional;

public interface StockCorporateActionRepository extends JpaRepository<StockCorporateAction, Long> {

    List<StockCorporateAction> findBySymbolOrderByCreatedAtDesc(String symbol);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockCorporateAction a where a.id = :id")
    Optional<StockCorporateAction> findByIdForUpdate(@Param("id") Long id);
}
