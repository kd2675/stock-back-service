package stock.back.service.market.act;

import auth.common.core.context.UserContext;
import org.junit.jupiter.api.Test;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.market.biz.BatchJobSignalService;
import stock.back.service.market.biz.MarketStatusService;
import stock.back.service.market.vo.MarketStatusUpdateRequest;
import stock.back.service.market.vo.SymbolMarketConfigResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketControllerTest {

    private final MarketStatusService marketStatusService = mock(MarketStatusService.class);
    private final BatchJobSignalService batchJobSignalService = mock(BatchJobSignalService.class);
    private final MarketController marketController = new MarketController(
            marketStatusService,
            batchJobSignalService
    );

    @Test
    void updateMarketStatus_orderBookClosed_runsSymbolMarketCloseRollover() {
        MarketStatusUpdateRequest request = new MarketStatusUpdateRequest(true, MarketSessionStatus.CLOSED);
        SymbolMarketConfigResponse marketResponse = new SymbolMarketConfigResponse(
                "MC001",
                true,
                MarketSessionStatus.CLOSED
        );
        when(marketStatusService.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request))
                .thenReturn(marketResponse);

        var response = marketController.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request, userContext());

        verify(batchJobSignalService).enqueueMarketCloseRollover("MC001", "admin-user-key");
        verify(batchJobSignalService, never()).enqueueOpenOrderBookOrderCancel("MC001", "admin-user-key");
        assertThat(response.getData()).isEqualTo(marketResponse);
    }

    @Test
    void updateMarketStatus_orderBookHalted_cancelsOpenOrderBookOrders() {
        MarketStatusUpdateRequest request = new MarketStatusUpdateRequest(true, MarketSessionStatus.HALTED);
        SymbolMarketConfigResponse marketResponse = new SymbolMarketConfigResponse(
                "MC001",
                true,
                MarketSessionStatus.HALTED
        );
        when(marketStatusService.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request))
                .thenReturn(marketResponse);

        var response = marketController.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request, userContext());

        verify(batchJobSignalService).enqueueOpenOrderBookOrderCancel("MC001", "admin-user-key");
        verify(batchJobSignalService, never()).enqueueMarketCloseRollover("MC001", "admin-user-key");
        assertThat(response.getData()).isEqualTo(marketResponse);
    }

    @Test
    void updateMarketStatus_orderBookCircuitBreaker_cancelsOpenOrderBookOrders() {
        MarketStatusUpdateRequest request = new MarketStatusUpdateRequest(true, MarketSessionStatus.CIRCUIT_BREAKER);
        SymbolMarketConfigResponse marketResponse = new SymbolMarketConfigResponse(
                "MC001",
                true,
                MarketSessionStatus.CIRCUIT_BREAKER
        );
        when(marketStatusService.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request))
                .thenReturn(marketResponse);

        var response = marketController.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request, userContext());

        verify(batchJobSignalService).enqueueOpenOrderBookOrderCancel("MC001", "admin-user-key");
        verify(batchJobSignalService, never()).enqueueMarketCloseRollover("MC001", "admin-user-key");
        assertThat(response.getData()).isEqualTo(marketResponse);
    }

    @Test
    void updateMarketStatus_orderBookOpen_doesNotRunSymbolMarketCloseRollover() {
        MarketStatusUpdateRequest request = new MarketStatusUpdateRequest(true, MarketSessionStatus.OPEN);
        SymbolMarketConfigResponse marketResponse = new SymbolMarketConfigResponse(
                "MC001",
                true,
                MarketSessionStatus.OPEN
        );
        when(marketStatusService.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request))
                .thenReturn(marketResponse);

        var response = marketController.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request, userContext());

        verify(batchJobSignalService, never()).enqueueMarketCloseRollover("MC001", "admin-user-key");
        verify(batchJobSignalService, never()).enqueueOpenOrderBookOrderCancel("MC001", "admin-user-key");
        assertThat(response.getData()).isEqualTo(marketResponse);
    }

    @Test
    void updateMarketStatus_virtualPriceClosed_doesNotRunSymbolMarketCloseRollover() {
        MarketStatusUpdateRequest request = new MarketStatusUpdateRequest(true, MarketSessionStatus.CLOSED);
        SymbolMarketConfigResponse marketResponse = new SymbolMarketConfigResponse(
                "MC001",
                true,
                MarketSessionStatus.CLOSED
        );
        when(marketStatusService.updateMarketStatus(MarketType.VIRTUAL_PRICE, "mc001", request))
                .thenReturn(marketResponse);

        var response = marketController.updateMarketStatus(MarketType.VIRTUAL_PRICE, "mc001", request, userContext());

        verify(batchJobSignalService, never()).enqueueMarketCloseRollover("MC001", "admin-user-key");
        verify(batchJobSignalService, never()).enqueueOpenOrderBookOrderCancel("MC001", "admin-user-key");
        assertThat(response.getData()).isEqualTo(marketResponse);
    }

    private UserContext userContext() {
        return UserContext.builder()
                .userKey("admin-user-key")
                .role("ROLE_ADMIN")
                .build();
    }
}
