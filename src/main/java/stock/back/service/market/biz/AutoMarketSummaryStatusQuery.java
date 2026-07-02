package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.market.vo.AutoMarketStatusResponse;

import java.time.LocalDateTime;

@Service
class AutoMarketSummaryStatusQuery {

    private static final String SALARY_ELIGIBLE_PARTICIPANT_COUNT_SQL = """
            select count(*)
              from stock_auto_participant p
              join stock_account a on a.user_key = p.user_key and a.status = 'ACTIVE'
              left join stock_auto_participant_profile_config pc on pc.profile_type = p.profile_type
             where p.enabled = true
               and p.withdrawn_at is null
               and p.profile_type <> 'DIVIDEND_REINVESTOR'
               and (
                   coalesce(p.recurring_cash_amount, 0) > 0
                   or coalesce(pc.recurring_deposit_amount, 0) > 0
               )
            """;

    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;

    AutoMarketSummaryStatusQuery(
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
        this.simulationClockService = simulationClockService;
    }

    AutoMarketStatusResponse getSummaryStatus(boolean includeRuntimeMetrics, boolean includeSalaryEligibility) {
        LocalDateTime todayStart = simulationClockService.currentMarketDayStart();
        String runtimeMetricSql = includeRuntimeMetrics
                ? """
                       (select count(*)
                          from stock_order o
                         where o.market_type = 'ORDER_BOOK'
                           and o.status in ('PENDING', 'PARTIALLY_FILLED')
                           and exists (
                               select 1
                                 from stock_account a
                                 join stock_auto_participant p on p.user_key = a.user_key
                                where a.id = o.account_id
                                  and p.enabled = true
                                  and p.withdrawn_at is null
                           )) as open_auto_order_count,
                       (select count(*)
                          from stock_execution e
                         where e.executed_at >= :todayStart
                           and exists (
                               select 1
                                 from stock_account a
                                 join stock_auto_participant p on p.user_key = a.user_key
                                where a.id = e.account_id
                                  and p.enabled = true
                                  and p.withdrawn_at is null
                           )) as today_auto_execution_count
                        """
                : """
                       0 as open_auto_order_count,
                       0 as today_auto_execution_count
                        """;
        String salaryEligibilitySql = includeSalaryEligibility
                ? "(" + SALARY_ELIGIBLE_PARTICIPANT_COUNT_SQL + ") as salary_eligible_participant_count"
                : "0 as salary_eligible_participant_count";
        String sql = """
                select (select count(*) from stock_auto_market_config) as config_count,
                       (select count(*)
                          from stock_auto_market_config c
                         where c.enabled = true) as enabled_config_count,
                       (select count(*)
                          from stock_auto_participant p
                         where p.withdrawn_at is null) as participant_count,
                       (select count(*)
                          from stock_auto_participant p
                         where p.enabled = true
                           and p.withdrawn_at is null) as enabled_participant_count,
                       (select count(*) from stock_listing_auto_account_config) as listing_auto_account_count,
                       %s,
                       %s
                """.formatted(salaryEligibilitySql, runtimeMetricSql);
        if (includeRuntimeMetrics) {
            return jdbcClient.sql(sql)
                    .param("todayStart", todayStart)
                    .query((rs, rowNum) -> AutoMarketStatusResponseMapper.toSummaryStatus(rs, AutoParticipantProfileType.values().length))
                    .single();
        }
        return jdbcClient.sql(sql)
                .query((rs, rowNum) -> AutoMarketStatusResponseMapper.toSummaryStatus(rs, AutoParticipantProfileType.values().length))
                .single();
    }

    long countSalaryEligibleAutoParticipants() {
        return jdbcClient.sql(SALARY_ELIGIBLE_PARTICIPANT_COUNT_SQL)
                .query(Long.class)
                .single();
    }
}
