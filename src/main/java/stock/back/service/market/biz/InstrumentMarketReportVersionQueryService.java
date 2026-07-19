package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstrumentMarketReportVersionQueryService {

    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;

    public InstrumentMarketReportVersionQueryService(
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true)
    public Long findLatestCloseRunId(String symbol) {
        return jdbcClient.sql(
                        """
                        select snapshot.close_run_id
                          from stock_order_book_daily_snapshot snapshot
                          join stock_market_close_run close_run
                            on close_run.id = snapshot.close_run_id
                           and close_run.symbol is null
                           and close_run.status = 'COMPLETED'
                          join stock_post_close_cycle close_cycle
                            on close_cycle.close_run_id = close_run.id
                           and close_cycle.scope_type = 'FULL_MARKET'
                           and close_cycle.scope_key = 'ALL'
                           and close_cycle.phase in (
                               'REPORTS_AGGREGATED', 'PREOPEN_SECURITY_TRANSFORMS_APPLIED',
                               'MARKET_DATA_PREPARED', 'AUTO_MARKET_PREPARED',
                               'READY_TO_OPEN', 'COMPLETED'
                           )
                         where snapshot.symbol = ?
                           and snapshot.simulation_trade_date <= ?
                         order by snapshot.simulation_trade_date desc,
                                  snapshot.close_run_id desc,
                                  snapshot.id desc
                         limit 1
                        """
                )
                .params(symbol, simulationClockService.currentDate())
                .query(Long.class)
                .optional()
                .orElse(null);
    }
}
