package stock.back.service.market.biz;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import stock.back.service.market.vo.InstrumentMarketReportResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstrumentMarketReportServiceTest {

    @Test
    void getInstrumentMarketReport_sameCloseRun_concurrentRequestsShareSingleLoad() throws Exception {
        InstrumentMarketReportQueryService queryService = mock(InstrumentMarketReportQueryService.class);
        InstrumentMarketReportVersionQueryService versionQueryService = mock(InstrumentMarketReportVersionQueryService.class);
        InstrumentMarketReportResponse report = mock(InstrumentMarketReportResponse.class);
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(versionQueryService.findLatestCloseRunId("DEMO001")).thenReturn(11L);
        when(report.closeRunId()).thenReturn(11L);
        when(queryService.getInstrumentMarketReport("DEMO001")).thenAnswer(ignored -> {
            loadStarted.countDown();
            assertThat(releaseLoad.await(2, TimeUnit.SECONDS)).isTrue();
            return report;
        });
        InstrumentMarketReportService service = new InstrumentMarketReportService(
                queryService,
                versionQueryService,
                new SimpleMeterRegistry()
        );

        CompletableFuture<InstrumentMarketReportResponse> first = CompletableFuture.supplyAsync(
                () -> service.getInstrumentMarketReport("DEMO001")
        );
        assertThat(loadStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<InstrumentMarketReportResponse> second = CompletableFuture.supplyAsync(
                () -> service.getInstrumentMarketReport("DEMO001")
        );
        releaseLoad.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS)).isSameAs(report);
        assertThat(second.get(2, TimeUnit.SECONDS)).isSameAs(report);
        assertThat(service.getInstrumentMarketReport("DEMO001")).isSameAs(report);
        verify(queryService, times(1)).getInstrumentMarketReport("DEMO001");
        verify(versionQueryService, times(1)).findLatestCloseRunId("DEMO001");
    }

    @Test
    void getInstrumentMarketReport_expiredVersionWithSameCloseRun_reusesReport() {
        InstrumentMarketReportQueryService queryService = mock(InstrumentMarketReportQueryService.class);
        InstrumentMarketReportVersionQueryService versionQueryService = mock(InstrumentMarketReportVersionQueryService.class);
        InstrumentMarketReportResponse report = mock(InstrumentMarketReportResponse.class);
        when(versionQueryService.findLatestCloseRunId("DEMO001")).thenReturn(11L);
        when(report.closeRunId()).thenReturn(11L);
        when(queryService.getInstrumentMarketReport("DEMO001")).thenReturn(report);
        InstrumentMarketReportService service = new InstrumentMarketReportService(
                queryService,
                versionQueryService,
                new SimpleMeterRegistry(),
                0L
        );

        assertThat(service.getInstrumentMarketReport("DEMO001")).isSameAs(report);
        assertThat(service.getInstrumentMarketReport("DEMO001")).isSameAs(report);

        verify(queryService, times(1)).getInstrumentMarketReport("DEMO001");
        verify(versionQueryService, times(2)).findLatestCloseRunId("DEMO001");
    }
}
