package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockAccount;

import java.util.Optional;

public interface StockAccountRepository extends JpaRepository<StockAccount, Long> {
    Optional<StockAccount> findByUserKey(String userKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockAccount a where a.userKey = :userKey")
    Optional<StockAccount> findByUserKeyForUpdate(@Param("userKey") String userKey);
}
