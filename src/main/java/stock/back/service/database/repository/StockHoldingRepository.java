package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockHolding;

import java.util.List;
import java.util.Optional;

public interface StockHoldingRepository extends JpaRepository<StockHolding, Long> {
    List<StockHolding> findByUserKeyOrderBySymbolAsc(String userKey);

    Optional<StockHolding> findByUserKeyAndSymbol(String userKey, String symbol);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from StockHolding h where h.userKey = :userKey and h.symbol = :symbol")
    Optional<StockHolding> findByUserKeyAndSymbolForUpdate(@Param("userKey") String userKey, @Param("symbol") String symbol);
}
