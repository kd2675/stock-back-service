package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockInstrumentReportEvent;

import java.util.List;
import java.util.Optional;

public interface StockInstrumentReportEventRepository extends JpaRepository<StockInstrumentReportEvent, Long> {

    List<StockInstrumentReportEvent> findTop50BySymbolOrderByCreatedAtDescIdDesc(String symbol);

    Optional<StockInstrumentReportEvent> findTopBySymbolOrderByCreatedAtDescIdDesc(String symbol);
}
