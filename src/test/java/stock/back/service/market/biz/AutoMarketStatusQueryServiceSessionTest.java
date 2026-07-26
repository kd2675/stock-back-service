package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantProfileConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.vo.AutoMarketStatusResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoMarketStatusQueryServiceSessionTest {

    @Test
    void getAutoMarketStatus_summaryOnlyOutsideRegularSession_reportsDisabled() {
        AutoMarketSummaryStatusQuery summaryStatusQuery = mock(AutoMarketSummaryStatusQuery.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        AutoMarketStatusResponse runningSummary = new AutoMarketStatusResponse(
                true,
                3L,
                10L,
                27L,
                8L,
                4L,
                5L,
                6L,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        when(summaryStatusQuery.getSummaryStatus(false, false)).thenReturn(runningSummary);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        AutoMarketStatusQueryService service = new AutoMarketStatusQueryService(
                mock(StockAutoMarketConfigRepository.class),
                mock(StockAutoParticipantProfileConfigRepository.class),
                mock(StockAutoParticipantRepository.class),
                mock(StockOrderRepository.class),
                mock(AutoMarketStatusDataLoader.class),
                summaryStatusQuery,
                mock(SimulationClockService.class),
                simulationMarketSessionService
        );

        AutoMarketStatusResponse response = service.getAutoMarketStatus(
                false,
                false,
                false,
                false,
                false,
                false,
                null
        );

        assertThat(response.enabled()).isFalse();
        assertThat(response.configCount()).isEqualTo(3L);
        assertThat(response.enabledParticipantCount()).isEqualTo(8L);
    }
}
