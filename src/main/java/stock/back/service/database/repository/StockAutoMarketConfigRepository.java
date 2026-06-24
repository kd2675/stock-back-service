package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockAutoMarketConfig;

import java.util.List;

public interface StockAutoMarketConfigRepository extends JpaRepository<StockAutoMarketConfig, String> {
    List<StockAutoMarketConfig> findByEnabledTrueOrderBySymbolAsc();
}
