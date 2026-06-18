package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockInstrument;

import java.util.List;

public interface StockInstrumentRepository extends JpaRepository<StockInstrument, String> {
    List<StockInstrument> findByEnabledTrueOrderBySymbolAsc();
}
