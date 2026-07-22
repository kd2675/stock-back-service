package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.back.service.market.vo.AutoParticipantPerformanceMetricResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AutoParticipantPerformanceSummaryQueryServiceTest {

    private final AutoParticipantPerformanceSummaryQueryService service =
            new AutoParticipantPerformanceSummaryQueryService(
                    mock(JdbcTemplate.class),
                    mock(AutoParticipantOverviewCacheService.class),
                    mock(SimulationClockService.class)
            );

    @Test
    void summarize_largeLossAndSmallGain_usesAggregateCapitalReturn() {
        AutoParticipantPerformanceMetricResponse result = service.summarize(List.of(
                row("500", "1000", "-500", "-50"),
                row("300", "100", "200", "200")
        ));

        assertThat(result.aggregateReturnRate()).isEqualByComparingTo(new BigDecimal("-27.27272727"));
    }

    @Test
    void summarize_largeLossAndSmallGain_keepsMedianAsDistributionMetric() {
        AutoParticipantPerformanceMetricResponse result = service.summarize(List.of(
                row("500", "1000", "-500", "-50"),
                row("300", "100", "200", "200")
        ));

        assertThat(result.medianAccountReturnRate()).isEqualByComparingTo(new BigDecimal("75.00000000"));
    }

    @Test
    void summarize_zeroContribution_excludesReturnButKeepsProfit() {
        AutoParticipantPerformanceMetricResponse result = service.summarize(List.of(
                new AutoParticipantPerformanceSummaryQueryService.PerformanceRow(
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        new BigDecimal("100"),
                        null,
                        PortfolioReturnRateStatus.UNDEFINED_ZERO_CONTRIBUTION
                )
        ));

        assertThat(result.totalProfit() + ":" + result.eligibleAccountCount() + ":" + result.undefinedAccountCount())
                .isEqualTo("100:0:1");
    }

    private AutoParticipantPerformanceSummaryQueryService.PerformanceRow row(
            String totalAsset,
            String netContribution,
            String totalProfit,
            String returnRate
    ) {
        return new AutoParticipantPerformanceSummaryQueryService.PerformanceRow(
                new BigDecimal(totalAsset),
                new BigDecimal(netContribution),
                new BigDecimal(totalProfit),
                new BigDecimal(returnRate),
                PortfolioReturnRateStatus.DEFINED
        );
    }
}
