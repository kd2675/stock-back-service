package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import stock.back.service.database.entity.StockPrice;

import java.util.List;

public interface StockPriceRepository extends JpaRepository<StockPrice, String> {
    List<StockPrice> findAllByOrderBySymbolAsc();

    @Query("""
            select price
            from StockPrice price
            join StockInstrument instrument on instrument.symbol = price.symbol
            where instrument.enabled = true
            order by price.symbol asc
            """)
    List<StockPrice> findVirtualMarketPrices();
}
