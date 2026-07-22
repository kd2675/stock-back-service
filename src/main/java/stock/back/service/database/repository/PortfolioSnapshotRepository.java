package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.PortfolioSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, Long> {
    @Query(value = """
            select ps.*
              from portfolio_snapshot ps
              left join stock_post_close_cycle cycle
                on cycle.id = ps.close_cycle_id
             where ps.snapshot_date = :snapshotDate
               and ps.return_rate_status = 'DEFINED'
               and ps.return_rate is not null
               and (
                   (ps.close_cycle_id is null and ps.close_run_id is null)
                   or (
                       cycle.scope_type = 'FULL_MARKET'
                       and cycle.scope_key = 'ALL'
                       and cycle.phase in (
                           'PORTFOLIO_SETTLED', 'OVERNIGHT_CASH_APPLIED', 'CORPORATE_CASH_APPLIED',
                           'REPORTS_AGGREGATED', 'PREOPEN_SECURITY_TRANSFORMS_APPLIED',
                           'MARKET_DATA_PREPARED', 'AUTO_MARKET_PREPARED', 'READY_TO_OPEN', 'COMPLETED'
                       )
                   )
               )
             order by ps.return_rate desc, ps.id asc
             limit 20
            """, nativeQuery = true)
    List<PortfolioSnapshot> findTop20BySnapshotDateOrderByReturnRateDesc(
            @Param("snapshotDate") LocalDate snapshotDate
    );

    @Query(value = """
            select ps.*
              from portfolio_snapshot ps
              left join stock_post_close_cycle cycle
                on cycle.id = ps.close_cycle_id
             where ps.account_id = :accountId
               and (
                   (ps.close_cycle_id is null and ps.close_run_id is null)
                   or (
                       cycle.scope_type = 'FULL_MARKET'
                       and cycle.scope_key = 'ALL'
                       and cycle.phase in (
                           'PORTFOLIO_SETTLED', 'OVERNIGHT_CASH_APPLIED', 'CORPORATE_CASH_APPLIED',
                           'REPORTS_AGGREGATED', 'PREOPEN_SECURITY_TRANSFORMS_APPLIED',
                           'MARKET_DATA_PREPARED', 'AUTO_MARKET_PREPARED', 'READY_TO_OPEN', 'COMPLETED'
                       )
                   )
               )
             order by ps.snapshot_date desc, ps.id desc
             limit 30
            """, nativeQuery = true)
    List<PortfolioSnapshot> findTop30ByAccountIdOrderBySnapshotDateDesc(@Param("accountId") Long accountId);

    @Query(value = """
            select ps.*
              from portfolio_snapshot ps
              left join stock_post_close_cycle cycle
                on cycle.id = ps.close_cycle_id
             where (
                   (ps.close_cycle_id is null and ps.close_run_id is null)
                   or (
                       cycle.scope_type = 'FULL_MARKET'
                       and cycle.scope_key = 'ALL'
                       and cycle.phase in (
                           'PORTFOLIO_SETTLED', 'OVERNIGHT_CASH_APPLIED', 'CORPORATE_CASH_APPLIED',
                           'REPORTS_AGGREGATED', 'PREOPEN_SECURITY_TRANSFORMS_APPLIED',
                           'MARKET_DATA_PREPARED', 'AUTO_MARKET_PREPARED', 'READY_TO_OPEN', 'COMPLETED'
                       )
                   )
               )
             order by ps.snapshot_date desc, ps.id desc
             limit 1
            """, nativeQuery = true)
    Optional<PortfolioSnapshot> findTopByOrderBySnapshotDateDesc();
}
