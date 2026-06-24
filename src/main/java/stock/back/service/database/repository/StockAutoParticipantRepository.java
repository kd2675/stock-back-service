package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockAutoParticipant;

import java.util.List;

public interface StockAutoParticipantRepository extends JpaRepository<StockAutoParticipant, String> {
    List<StockAutoParticipant> findByWithdrawnAtIsNullOrderByUserKeyAsc();

    long countByEnabledTrueAndWithdrawnAtIsNull();
}
