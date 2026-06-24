package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockOrderBookMarketConfig;

public interface StockOrderBookMarketConfigRepository extends JpaRepository<StockOrderBookMarketConfig, String> {

    long countByEnabledTrue();
}
