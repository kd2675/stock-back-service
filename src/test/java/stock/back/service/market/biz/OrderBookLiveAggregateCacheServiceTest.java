package stock.back.service.market.biz;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import stock.back.service.market.vo.OrderBookCandleResponse;
import stock.back.service.market.vo.OrderBookTradeSummaryResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderBookLiveAggregateCacheServiceTest {

    @Test
    void getTradeSummary_concurrentEquivalentRequestsShareSingleLedgerQuery() throws Exception {
        OrderBookQueryService queryService = mock(OrderBookQueryService.class);
        OrderBookCandleQueryService candleQueryService = mock(OrderBookCandleQueryService.class);
        OrderBookTradeSummaryResponse summary = mock(OrderBookTradeSummaryResponse.class);
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(queryService.getOrderBookTradeSummary("DEMO001")).thenAnswer(ignored -> {
            loadStarted.countDown();
            assertThat(releaseLoad.await(2, TimeUnit.SECONDS)).isTrue();
            return summary;
        });
        OrderBookLiveAggregateCacheService service = service(queryService, candleQueryService);

        CompletableFuture<OrderBookTradeSummaryResponse> first = CompletableFuture.supplyAsync(
                () -> service.getTradeSummary("demo001")
        );
        assertThat(loadStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<OrderBookTradeSummaryResponse> second = CompletableFuture.supplyAsync(
                () -> service.getTradeSummary(" DEMO001 ")
        );
        releaseLoad.countDown();

        assertThat(List.of(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS)))
                .containsOnly(summary);
        verify(queryService, times(1)).getOrderBookTradeSummary("DEMO001");
    }

    @Test
    void getCandles_repeatedEquivalentRequestsReuseBoundedResult() {
        OrderBookQueryService queryService = mock(OrderBookQueryService.class);
        OrderBookCandleQueryService candleQueryService = mock(OrderBookCandleQueryService.class);
        List<OrderBookCandleResponse> candles = List.of(mock(OrderBookCandleResponse.class));
        when(candleQueryService.getOrderBookCandles("DEMO002", "1M")).thenReturn(candles);
        OrderBookLiveAggregateCacheService service = service(queryService, candleQueryService);

        List<OrderBookCandleResponse> first = service.getCandles("demo002", "1m");
        List<OrderBookCandleResponse> second = service.getCandles("DEMO002", "1M");

        assertThat(List.of(first, second)).allSatisfy(result -> assertThat(result).containsExactlyElementsOf(candles));
        verify(candleQueryService, times(1)).getOrderBookCandles("DEMO002", "1M");
    }

    private OrderBookLiveAggregateCacheService service(
            OrderBookQueryService queryService,
            OrderBookCandleQueryService candleQueryService
    ) {
        return new OrderBookLiveAggregateCacheService(
                queryService,
                candleQueryService,
                new SimpleMeterRegistry(),
                10_000L,
                10_000L,
                100
        );
    }
}
