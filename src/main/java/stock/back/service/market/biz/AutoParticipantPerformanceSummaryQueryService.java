package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.market.vo.AutoParticipantActivityScope;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;
import stock.back.service.market.vo.AutoParticipantPerformanceBasis;
import stock.back.service.market.vo.AutoParticipantPerformanceMetricResponse;
import stock.back.service.market.vo.AutoParticipantPerformanceSummaryResponse;
import stock.back.service.market.vo.SimulationClockResponse;

@Service
public class AutoParticipantPerformanceSummaryQueryService {

    private static final String CALCULATION_METHOD = "NET_CONTRIBUTION_RETURN";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbcClient;
    private final AutoParticipantOverviewCacheService overviewCacheService;
    private final SimulationClockService simulationClockService;

    public AutoParticipantPerformanceSummaryQueryService(
            JdbcTemplate jdbcTemplate,
            AutoParticipantOverviewCacheService overviewCacheService,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.overviewCacheService = overviewCacheService;
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true)
    public AutoParticipantPerformanceSummaryResponse getPerformanceSummary(
            AutoParticipantPerformanceBasis basis
    ) {
        AutoParticipantPerformanceBasis effectiveBasis = basis == null
                ? AutoParticipantPerformanceBasis.LIVE_ESTIMATE
                : basis;
        if (effectiveBasis == AutoParticipantPerformanceBasis.LATEST_CLOSED) {
            return latestClosedSummary();
        }
        return liveSummary();
    }

    private AutoParticipantPerformanceSummaryResponse liveSummary() {
        List<PerformanceRow> rows = overviewCacheService.getAutoParticipantOverviews(
                        false,
                        List.of(),
                        AutoParticipantActivityScope.RECENT_SIMULATION_DAY
                ).stream()
                .filter(overview -> overview.accountId() != null)
                .filter(overview -> "ACTIVE".equals(overview.accountStatus()))
                .map(this::toLiveRow)
                .toList();
        SimulationClockResponse clock = simulationClockService.currentResponse();
        LocalDateTime calculatedAt = clock.simulationDateTime();
        LocalDate businessDate = clock.activeBusinessDate() == null
                ? calculatedAt.toLocalDate()
                : clock.activeBusinessDate();
        return new AutoParticipantPerformanceSummaryResponse(
                AutoParticipantPerformanceBasis.LIVE_ESTIMATE,
                businessDate,
                calculatedAt,
                CALCULATION_METHOD,
                null,
                summarize(rows)
        );
    }

    private PerformanceRow toLiveRow(AutoParticipantOverviewResponse overview) {
        return new PerformanceRow(
                overview.estimatedTotalAsset(),
                overview.netCashFlow(),
                overview.totalProfit(),
                overview.returnRate(),
                PortfolioReturnRateStatus.valueOf(overview.returnRateStatus())
        );
    }

    private AutoParticipantPerformanceSummaryResponse latestClosedSummary() {
        ClosedCycle cycle = jdbcClient.sql(
                        """
                        select cycle.id,
                               cycle.business_date,
                               max(portfolio.created_at) as calculated_at
                          from stock_post_close_cycle cycle
                          join portfolio_snapshot portfolio
                            on portfolio.close_cycle_id = cycle.id
                         where cycle.scope_type = 'FULL_MARKET'
                           and cycle.scope_key = 'ALL'
                           and cycle.cycle_kind = 'TRADING'
                           and cycle.phase in (
                               'PORTFOLIO_SETTLED', 'OVERNIGHT_CASH_APPLIED', 'CORPORATE_CASH_APPLIED',
                               'REPORTS_AGGREGATED', 'PREOPEN_SECURITY_TRANSFORMS_APPLIED',
                               'MARKET_DATA_PREPARED', 'AUTO_MARKET_PREPARED', 'READY_TO_OPEN', 'COMPLETED'
                           )
                         group by cycle.id, cycle.business_date
                         order by cycle.business_date desc, cycle.id desc
                         limit 1
                        """
                )
                .query((rs, rowNum) -> new ClosedCycle(
                        rs.getLong("id"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("calculated_at", LocalDateTime.class)
                ))
                .optional()
                .orElse(null);
        if (cycle == null) {
            return new AutoParticipantPerformanceSummaryResponse(
                    AutoParticipantPerformanceBasis.LATEST_CLOSED,
                    null,
                    null,
                    CALCULATION_METHOD,
                    null,
                    summarize(List.of())
            );
        }
        List<PerformanceRow> rows = jdbcClient.sql(
                        """
                        select portfolio.total_asset,
                               portfolio.net_contribution,
                               portfolio.total_profit,
                               portfolio.return_rate,
                               portfolio.return_rate_status
                          from stock_close_account_snapshot account_snapshot
                          join portfolio_snapshot portfolio
                            on portfolio.close_cycle_id = account_snapshot.close_cycle_id
                           and portfolio.account_id = account_snapshot.account_id
                         where account_snapshot.close_cycle_id = ?
                           and account_snapshot.participant_category = 'AUTO_PARTICIPANT'
                           and account_snapshot.settlement_target = true
                           and portfolio.return_rate_status <> 'LEGACY_UNVERIFIED'
                         order by account_snapshot.account_id
                        """
                )
                .param(cycle.id())
                .query((rs, rowNum) -> new PerformanceRow(
                        rs.getBigDecimal("total_asset"),
                        rs.getBigDecimal("net_contribution"),
                        rs.getBigDecimal("total_profit"),
                        rs.getBigDecimal("return_rate"),
                        PortfolioReturnRateStatus.valueOf(rs.getString("return_rate_status"))
                ))
                .list();
        return new AutoParticipantPerformanceSummaryResponse(
                AutoParticipantPerformanceBasis.LATEST_CLOSED,
                cycle.businessDate(),
                cycle.calculatedAt(),
                CALCULATION_METHOD,
                cycle.id(),
                summarize(rows)
        );
    }

    AutoParticipantPerformanceMetricResponse summarize(List<PerformanceRow> rows) {
        BigDecimal totalAsset = BigDecimal.ZERO;
        BigDecimal netContribution = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        List<BigDecimal> eligibleReturnRates = new ArrayList<>();
        long profitableAccountCount = 0;
        for (PerformanceRow row : rows) {
            totalAsset = totalAsset.add(row.totalAsset());
            netContribution = netContribution.add(row.netContribution());
            totalProfit = totalProfit.add(row.totalProfit());
            if (row.returnRateStatus() == PortfolioReturnRateStatus.DEFINED && row.returnRate() != null) {
                eligibleReturnRates.add(row.returnRate());
                if (row.totalProfit().compareTo(BigDecimal.ZERO) > 0) {
                    profitableAccountCount++;
                }
            }
        }
        BigDecimal aggregateReturnRate = netContribution.compareTo(BigDecimal.ZERO) > 0
                ? totalProfit.multiply(ONE_HUNDRED).divide(netContribution, 8, RoundingMode.HALF_UP)
                : null;
        BigDecimal medianReturnRate = median(eligibleReturnRates);
        BigDecimal profitableAccountRate = eligibleReturnRates.isEmpty()
                ? null
                : BigDecimal.valueOf(profitableAccountCount)
                        .multiply(ONE_HUNDRED)
                        .divide(BigDecimal.valueOf(eligibleReturnRates.size()), 8, RoundingMode.HALF_UP);
        return new AutoParticipantPerformanceMetricResponse(
                rows.size(),
                eligibleReturnRates.size(),
                rows.size() - eligibleReturnRates.size(),
                totalAsset,
                netContribution,
                totalProfit,
                aggregateReturnRate,
                medianReturnRate,
                profitableAccountCount,
                profitableAccountRate
        );
    }

    private BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1)
                .add(sorted.get(middle))
                .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    }

    record PerformanceRow(
            BigDecimal totalAsset,
            BigDecimal netContribution,
            BigDecimal totalProfit,
            BigDecimal returnRate,
            PortfolioReturnRateStatus returnRateStatus
    ) {
    }

    private record ClosedCycle(long id, LocalDate businessDate, LocalDateTime calculatedAt) {
    }
}
