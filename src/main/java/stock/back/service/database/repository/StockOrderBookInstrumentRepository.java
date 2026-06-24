package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockOrderBookInstrument;

import java.util.List;

public interface StockOrderBookInstrumentRepository extends JpaRepository<StockOrderBookInstrument, String> {

    boolean existsBySymbolAndEnabledTrue(String symbol);

    List<StockOrderBookInstrument> findByEnabledTrueOrderBySymbolAsc();
}
