package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InstrumentMarketReportAnalyticsQueryServiceTest {

    private static final LocalDateTime REPORT_END = LocalDateTime.of(2026, 10, 5, 0, 0);

    @Test
    void calculateDailyVolatility_usesSampleStandardDeviationOfReturns() {
        BigDecimal volatility = InstrumentMarketReportAnalyticsQueryService.calculateDailyVolatility(List.of(
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(110),
                BigDecimal.valueOf(99)
        ));

        assertThat(volatility).isEqualByComparingTo("14.1421");
    }

    @Test
    void calculateDailyVolatility_singleReturn_isInsufficient() {
        BigDecimal volatility = InstrumentMarketReportAnalyticsQueryService.calculateDailyVolatility(List.of(
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(110)
        ));

        assertThat(volatility).isNull();
    }

    @Test
    void corporateActionStatusAt_changeAfterReportDate_isNotIncluded() {
        StockCorporateActionStatus status = InstrumentMarketReportAnalyticsQueryService.corporateActionStatusAt(
                StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                REPORT_END.minusDays(2),
                REPORT_END.plusHours(1),
                REPORT_END.plusDays(3),
                REPORT_END
        );

        assertThat(status).isEqualTo(StockCorporateActionStatus.EX_RIGHTS_APPLIED);
    }

    @Test
    void corporateActionStatusAt_listingBeforeReportDate_isIncluded() {
        StockCorporateActionStatus status = InstrumentMarketReportAnalyticsQueryService.corporateActionStatusAt(
                StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                REPORT_END.minusDays(4),
                REPORT_END.minusDays(2),
                REPORT_END.minusHours(1),
                REPORT_END
        );

        assertThat(status).isEqualTo(StockCorporateActionStatus.LISTED);
    }

    @Test
    void corporateActionStatusAt_delistingUsesAppliedTimestamp() {
        StockCorporateActionStatus status = InstrumentMarketReportAnalyticsQueryService.corporateActionStatusAt(
                StockCorporateActionType.DELISTING,
                REPORT_END.minusMinutes(1),
                null,
                null,
                REPORT_END
        );

        assertThat(status).isEqualTo(StockCorporateActionStatus.DELISTED);
    }
}
