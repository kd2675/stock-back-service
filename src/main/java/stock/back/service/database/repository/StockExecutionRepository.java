package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.StockExecution;

import java.math.BigDecimal;
import java.util.List;

public interface StockExecutionRepository extends JpaRepository<StockExecution, Long> {
    List<StockExecution> findTop50ByAccountIdOrderByExecutedAtDesc(Long accountId);

    List<StockExecution> findTop50ByAccountIdAndSourceOrderByExecutedAtDesc(Long accountId, ExecutionSource source);

    @Query(value = """
            select
              coalesce(sum(case when side = 'BUY' then gross_amount else 0 end), 0) as buyGrossAmount,
              coalesce(sum(case when side = 'SELL' then gross_amount else 0 end), 0) as sellGrossAmount,
              coalesce(sum(case when side = 'BUY' then net_amount else 0 end), 0) as buyNetAmount,
              coalesce(sum(case when side = 'SELL' then net_amount else 0 end), 0) as sellNetAmount,
              coalesce(sum(fee_amount), 0) as totalFeeAmount,
              coalesce(sum(tax_amount), 0) as totalTaxAmount,
              coalesce(sum(realized_profit), 0) as realizedProfit,
              count(*) as executionCount
            from stock_execution
            where account_id = :accountId
            """, nativeQuery = true)
    ProfitSummaryProjection summarizeProfitByAccountId(@Param("accountId") Long accountId);

    interface ProfitSummaryProjection {
        BigDecimal getBuyGrossAmount();

        BigDecimal getSellGrossAmount();

        BigDecimal getBuyNetAmount();

        BigDecimal getSellNetAmount();

        BigDecimal getTotalFeeAmount();

        BigDecimal getTotalTaxAmount();

        BigDecimal getRealizedProfit();

        long getExecutionCount();
    }
}
