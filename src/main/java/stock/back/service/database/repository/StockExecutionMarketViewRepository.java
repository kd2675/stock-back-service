package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.StockExecution;

import java.time.LocalDateTime;

public interface StockExecutionMarketViewRepository extends Repository<StockExecution, Long> {

    @Query("""
            select count(e)
            from StockExecution e
            where e.executedAt >= :from
              and e.executedAt <= :to
              and e.source = :source
            """)
    long countExecutionsBetweenBySource(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("source") ExecutionSource source
    );

    @Query("""
            select count(e)
            from StockExecution e
            where e.executedAt >= :from
              and e.executedAt <= :to
              and e.accountId in (
                  select a.id
                  from StockAccount a
                  join StockAutoParticipant p on p.userKey = a.userKey
                  where p.enabled = true
                    and p.withdrawnAt is null
              )
            """)
    long countAutoExecutionsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
