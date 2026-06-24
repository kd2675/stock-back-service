package stock.back.service.database.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountStatus;

import java.util.Optional;

public interface StockAccountRepository extends JpaRepository<StockAccount, Long> {
    Optional<StockAccount> findByUserKey(String userKey);

    Optional<StockAccount> findByUserKeyAndStatus(String userKey, StockAccountStatus status);

    Optional<StockAccount> findByAccountCode(String accountCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockAccount a where a.userKey = :userKey and a.status = :status")
    Optional<StockAccount> findByUserKeyAndStatusForUpdate(@Param("userKey") String userKey, @Param("status") StockAccountStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockAccount a where a.accountCode = :accountCode")
    Optional<StockAccount> findByAccountCodeForUpdate(@Param("accountCode") String accountCode);
}
