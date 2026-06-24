package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockListingAutoAccountConfig;

import java.util.List;

public interface StockListingAutoAccountConfigRepository extends JpaRepository<StockListingAutoAccountConfig, String> {

    List<StockListingAutoAccountConfig> findAllByOrderBySymbolAsc();
}
