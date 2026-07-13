package stock.back.service.market.vo;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InstrumentMarketAnalyticsResponseContractTest {

    @Test
    void analyticsResponse_publicSections_matchFrontendContract() {
        assertThat(componentNames(InstrumentMarketAnalyticsResponse.class)).containsExactly(
                "performance",
                "tradingActivity",
                "investorFlow",
                "ownership",
                "corporateActions",
                "rankings",
                "dataQuality"
        );
    }

    @Test
    void periodMetrics_doNotExposeCurrentDayLabels() {
        assertThat(componentNames(InstrumentMarketAnalyticsResponse.TradingActivity.class)).containsExactly(
                "executionCount20Days",
                "executionQuantity20Days",
                "averageExecutionQuantity20Days",
                "averageSecondsBetweenTrades20Days"
        );
        assertThat(componentNames(InstrumentMarketAnalyticsResponse.InvestorFlow.class)).containsSubsequence(
                "autoParticipantExecutionShareRateLatestTradingDay",
                "topAccountExecutionShareRate20Days"
        );
        assertThat(componentNames(InstrumentMarketAnalyticsResponse.DataQuality.class)).contains(
                "reportDate",
                "hasReportDateTrades",
                "reportDateMarketCloseCompleted"
        );
    }

    @Test
    void corporateActionMetric_exposesBeforeAndAfterShareCounts() {
        assertThat(componentNames(InstrumentMarketAnalyticsResponse.CorporateActionMetric.class)).containsSubsequence(
                "beforePrice",
                "afterPrice",
                "beforeIssuedShares",
                "afterIssuedShares",
                "beforeMarketCapitalization",
                "afterMarketCapitalization"
        );
    }

    @Test
    void reportContract_doesNotExposeLiveOrderBookOrOperatorState() {
        assertThat(componentNames(InstrumentMarketAnalyticsResponse.class)).doesNotContain("liquidity");
        assertThat(componentNames(InstrumentMarketAnalyticsResponse.Ownership.class)).doesNotContain(
                "openSellQuantity",
                "underwriter"
        );
        assertThat(componentNames(InstrumentMarketAnalyticsResponse.Rankings.class)).doesNotContain(
                "liquidity"
        );
    }

    @Test
    void dataQuality_exposesNotesAndCalculationLimitations() {
        assertThat(componentNames(InstrumentMarketAnalyticsResponse.DataQuality.class)).startsWith(
                "level",
                "notes",
                "limitations"
        );
    }

    private List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
