package stock.back.service.database.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;

public interface StockAutoParticipantProfileConfigRepository extends JpaRepository<StockAutoParticipantProfileConfig, AutoParticipantProfileType> {
    List<StockAutoParticipantProfileConfig> findAllByOrderByProfileTypeAsc();
}
