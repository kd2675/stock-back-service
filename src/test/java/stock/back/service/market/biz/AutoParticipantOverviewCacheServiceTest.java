package stock.back.service.market.biz;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import stock.back.service.market.vo.AutoParticipantActivityScope;
import stock.back.service.market.vo.AutoParticipantLifecycleScope;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoParticipantOverviewCacheServiceTest {

    @Test
    void getAutoParticipantOverviews_concurrentEquivalentRequestsShareSingleLoad() throws Exception {
        AutoParticipantOverviewQueryService participantQueryService = mock(AutoParticipantOverviewQueryService.class);
        AutoParticipantProfileOverviewQueryService profileQueryService = mock(AutoParticipantProfileOverviewQueryService.class);
        List<AutoParticipantOverviewResponse> result = List.of();
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(participantQueryService.getAutoParticipantOverviews(
                true,
                List.of("account-a", "account-b"),
                AutoParticipantActivityScope.RECENT_SIMULATION_DAY,
                AutoParticipantLifecycleScope.CURRENT
        )).thenAnswer(ignored -> {
            loadStarted.countDown();
            assertThat(releaseLoad.await(2, TimeUnit.SECONDS)).isTrue();
            return result;
        });
        AutoParticipantOverviewCacheService service = new AutoParticipantOverviewCacheService(
                participantQueryService,
                profileQueryService,
                new SimpleMeterRegistry(),
                5_000L
        );

        CompletableFuture<List<AutoParticipantOverviewResponse>> first = CompletableFuture.supplyAsync(
                () -> service.getAutoParticipantOverviews(
                        true,
                        List.of("account-b", "account-a"),
                        AutoParticipantActivityScope.RECENT_SIMULATION_DAY
                )
        );
        assertThat(loadStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<List<AutoParticipantOverviewResponse>> second = CompletableFuture.supplyAsync(
                () -> service.getAutoParticipantOverviews(
                        true,
                        List.of("account-a", "account-b"),
                        AutoParticipantActivityScope.RECENT_SIMULATION_DAY
                )
        );
        releaseLoad.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS)).isSameAs(result);
        assertThat(second.get(2, TimeUnit.SECONDS)).isSameAs(result);
        verify(participantQueryService, times(1)).getAutoParticipantOverviews(
                true,
                List.of("account-a", "account-b"),
                AutoParticipantActivityScope.RECENT_SIMULATION_DAY,
                AutoParticipantLifecycleScope.CURRENT
        );
    }

    @Test
    void getAutoParticipantOverviews_differentLifecycleScopes_useSeparateCacheEntries() {
        AutoParticipantOverviewQueryService participantQueryService = mock(AutoParticipantOverviewQueryService.class);
        AutoParticipantProfileOverviewQueryService profileQueryService = mock(AutoParticipantProfileOverviewQueryService.class);
        List<AutoParticipantOverviewResponse> currentResult = List.of(mock(AutoParticipantOverviewResponse.class));
        List<AutoParticipantOverviewResponse> withdrawnResult = List.of(mock(AutoParticipantOverviewResponse.class));
        when(participantQueryService.getAutoParticipantOverviews(
                true,
                List.of(),
                AutoParticipantActivityScope.ALL,
                AutoParticipantLifecycleScope.CURRENT
        )).thenReturn(currentResult);
        when(participantQueryService.getAutoParticipantOverviews(
                true,
                List.of(),
                AutoParticipantActivityScope.ALL,
                AutoParticipantLifecycleScope.WITHDRAWN
        )).thenReturn(withdrawnResult);
        AutoParticipantOverviewCacheService service = new AutoParticipantOverviewCacheService(
                participantQueryService,
                profileQueryService,
                new SimpleMeterRegistry(),
                5_000L
        );

        List<AutoParticipantOverviewResponse> current = service.getAutoParticipantOverviews(
                true,
                List.of(),
                AutoParticipantActivityScope.ALL,
                AutoParticipantLifecycleScope.CURRENT
        );
        List<AutoParticipantOverviewResponse> withdrawn = service.getAutoParticipantOverviews(
                true,
                List.of(),
                AutoParticipantActivityScope.ALL,
                AutoParticipantLifecycleScope.WITHDRAWN
        );

        assertThat(List.of(current, withdrawn)).containsExactly(currentResult, withdrawnResult);
        verify(participantQueryService).getAutoParticipantOverviews(
                true,
                List.of(),
                AutoParticipantActivityScope.ALL,
                AutoParticipantLifecycleScope.CURRENT
        );
        verify(participantQueryService).getAutoParticipantOverviews(
                true,
                List.of(),
                AutoParticipantActivityScope.ALL,
                AutoParticipantLifecycleScope.WITHDRAWN
        );
    }
}
