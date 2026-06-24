package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockVirtualMarketConfig;

public interface StockVirtualMarketConfigRepository extends JpaRepository<StockVirtualMarketConfig, String> {

    long countByEnabledTrue();
}
