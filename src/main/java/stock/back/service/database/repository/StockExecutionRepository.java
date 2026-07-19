package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.StockExecution;

import java.math.BigDecimal;
import java.util.List;

public interface StockExecutionRepository extends JpaRepository<StockExecution, Long> {
    List<StockExecution> findByAccountIdOrderByExecutedAtDesc(Long accountId, Pageable pageable);

    List<StockExecution> findByAccountIdAndSourceOrderByExecutedAtDesc(Long accountId, ExecutionSource source, Pageable pageable);

    List<StockExecution> findByAccountIdAndSymbolOrderByExecutedAtDesc(Long accountId, String symbol, Pageable pageable);

    List<StockExecution> findByAccountIdAndSourceAndSymbolOrderByExecutedAtDesc(
            Long accountId,
            ExecutionSource source,
            String symbol,
            Pageable pageable
    );

    @Query(value = """
            select
              coalesce(sum(buy_gross_amount), 0) as buyGrossAmount,
              coalesce(sum(sell_gross_amount), 0) as sellGrossAmount,
              coalesce(sum(buy_net_amount), 0) as buyNetAmount,
              coalesce(sum(sell_net_amount), 0) as sellNetAmount,
              coalesce(sum(fee_amount), 0) as totalFeeAmount,
              coalesce(sum(tax_amount), 0) as totalTaxAmount,
              coalesce(sum(realized_profit), 0) as realizedProfit,
              coalesce(sum(execution_count), 0) as executionCount
            from stock_execution_account_day_summary
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
