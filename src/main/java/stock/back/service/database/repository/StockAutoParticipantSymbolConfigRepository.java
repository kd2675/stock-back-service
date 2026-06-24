package stock.back.service.database.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfigId;

public interface StockAutoParticipantSymbolConfigRepository extends JpaRepository<StockAutoParticipantSymbolConfig, StockAutoParticipantSymbolConfigId> {
    List<StockAutoParticipantSymbolConfig> findAllByOrderByUserKeyAscSymbolAsc();
}
