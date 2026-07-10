package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockOrderBookInstrument;

import java.util.List;
import java.util.Optional;

public interface StockOrderBookInstrumentRepository extends JpaRepository<StockOrderBookInstrument, String> {

    boolean existsBySymbolAndEnabledTrue(String symbol);

    long countByEnabledTrue();

    List<StockOrderBookInstrument> findByEnabledTrueOrderBySymbolAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from StockOrderBookInstrument i where i.symbol = :symbol")
    Optional<StockOrderBookInstrument> findByIdForUpdate(@Param("symbol") String symbol);
}
