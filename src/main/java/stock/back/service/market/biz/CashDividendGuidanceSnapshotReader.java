package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CashDividendGuidanceSnapshotReader {

    private final JdbcClient jdbcClient;

    public Optional<HoldingReference> findLatestCompletedFullMarketHolding(String symbol) {
        return jdbcClient.sql(
                        """
                        select close_run.id as close_run_id,
                               close_run.business_date,
                               coalesce(sum(snapshot.quantity), 0) as holding_quantity
                          from stock_market_close_run close_run
                          join stock_post_close_cycle close_cycle
                            on close_cycle.close_run_id = close_run.id
                           and close_cycle.scope_type = 'FULL_MARKET'
                           and close_cycle.scope_key = 'ALL'
                          left join stock_holding_snapshot snapshot
                            on snapshot.close_run_id = close_run.id
                           and snapshot.symbol = :symbol
                         where close_run.status = 'COMPLETED'
                           and close_run.symbol is null
                         group by close_run.id, close_run.business_date
                         order by close_run.business_date desc, close_run.id desc
                         limit 1
                        """
                )
                .param("symbol", symbol)
                .query((rs, rowNum) -> new HoldingReference(
                        rs.getLong("close_run_id"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getLong("holding_quantity")
                ))
                .optional();
    }

    public record HoldingReference(
            long closeRunId,
            LocalDate businessDate,
            long holdingQuantity
    ) {
    }
}
