package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockPrice;

import java.util.List;

public interface StockPriceRepository extends JpaRepository<StockPrice, String> {
    List<StockPrice> findAllByOrderBySymbolAsc();
}
