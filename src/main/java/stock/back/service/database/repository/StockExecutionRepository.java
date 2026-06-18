package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockExecution;

import java.util.List;

public interface StockExecutionRepository extends JpaRepository<StockExecution, Long> {
    List<StockExecution> findTop50ByUserKeyOrderByExecutedAtDesc(String userKey);
}
