package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockCorporateAction;

import java.util.List;

public interface StockCorporateActionRepository extends JpaRepository<StockCorporateAction, Long> {

    List<StockCorporateAction> findBySymbolOrderByCreatedAtDesc(String symbol);
}
