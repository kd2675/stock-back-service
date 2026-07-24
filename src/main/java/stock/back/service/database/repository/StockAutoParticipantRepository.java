package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockAutoParticipant;

import java.util.List;
import java.util.Optional;

public interface StockAutoParticipantRepository extends JpaRepository<StockAutoParticipant, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StockAutoParticipant p where p.userKey = :userKey")
    Optional<StockAutoParticipant> findByUserKeyForUpdate(@Param("userKey") String userKey);

    List<StockAutoParticipant> findByWithdrawnAtIsNullOrderByUserKeyAsc();

    List<StockAutoParticipant> findByUserKeyInAndWithdrawnAtIsNull(List<String> userKeys);

    long countByWithdrawnAtIsNull();

    long countByEnabledTrueAndWithdrawnAtIsNull();
}
