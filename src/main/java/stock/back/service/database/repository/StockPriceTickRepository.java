package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockPriceTick;

import java.util.List;

public interface StockPriceTickRepository extends JpaRepository<StockPriceTick, Long> {
    List<StockPriceTick> findTop100BySymbolOrderByPriceTimeDesc(String symbol);
}
