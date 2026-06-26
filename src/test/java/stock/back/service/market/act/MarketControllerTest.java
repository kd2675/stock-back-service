package stock.back.service.market.act;

import auth.common.core.context.UserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.market.biz.MarketService;
import stock.back.service.market.client.StockBatchAdminClient;
import stock.back.service.market.stream.PriceStreamService;
import stock.back.service.market.vo.AutoParticipantCashFlowControlRequest;
import stock.back.service.market.vo.AutoParticipantCashFlowStatusResponse;
import stock.back.service.market.vo.BatchJobRuntimeControlRequest;
import stock.back.service.market.vo.BatchJobRuntimeStatusResponse;
import stock.back.service.market.vo.MarketStatusUpdateRequest;
import stock.back.service.market.vo.StockBatchJobRunResponse;
import stock.back.service.market.vo.SymbolMarketConfigResponse;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketControllerTest {

    private final MarketService marketService = mock(MarketService.class);
    private final PriceStreamService priceStreamService = mock(PriceStreamService.class);
    private final StockBatchAdminClient stockBatchAdminClient = mock(StockBatchAdminClient.class);
    private final MarketController marketController = new MarketController(
            marketService,
            priceStreamService,
            stockBatchAdminClient
    );

    @Test
    void updateAutoParticipantCashFlowStatus_adminUserKey_overridesClientUpdatedBy() {
        AutoParticipantCashFlowStatusResponse batchResponse = new AutoParticipantCashFlowStatusResponse(
                true,
                false,
                false,
                "admin-user-key",
                LocalDateTime.now()
        );
        when(stockBatchAdminClient.updateAutoParticipantCashFlowStatus(
                org.mockito.ArgumentMatchers.any(AutoParticipantCashFlowControlRequest.class)
        )).thenReturn(batchResponse);

        var response = marketController.updateAutoParticipantCashFlowStatus(
                new AutoParticipantCashFlowControlRequest(false, "client-forged-user"),
                UserContext.builder()
                        .userKey("admin-user-key")
                        .role("ROLE_ADMIN")
                        .build()
        );

        ArgumentCaptor<AutoParticipantCashFlowControlRequest> commandCaptor =
                ArgumentCaptor.forClass(AutoParticipantCashFlowControlRequest.class);
        verify(stockBatchAdminClient).updateAutoParticipantCashFlowStatus(commandCaptor.capture());
        AutoParticipantCashFlowControlRequest command = commandCaptor.getValue();
        assertThat(command.runtimeEnabled()).isFalse();
        assertThat(command.updatedBy()).isEqualTo("admin-user-key");
        assertThat(response.getData()).isEqualTo(batchResponse);
    }

    @Test
    void updateBatchJobRuntimeControl_adminUserKey_overridesClientUpdatedBy() {
        BatchJobRuntimeStatusResponse batchResponse = new BatchJobRuntimeStatusResponse(
                "auto-market",
                true,
                false,
                false,
                "admin-user-key",
                LocalDateTime.now()
        );
        when(stockBatchAdminClient.updateBatchJobRuntimeControl(
                org.mockito.ArgumentMatchers.eq("auto-market"),
                org.mockito.ArgumentMatchers.any(BatchJobRuntimeControlRequest.class)
        )).thenReturn(batchResponse);

        var response = marketController.updateBatchJobRuntimeControl(
                "auto-market",
                new BatchJobRuntimeControlRequest(false, "client-forged-user"),
                UserContext.builder()
                        .userKey("admin-user-key")
                        .role("ROLE_ADMIN")
                        .build()
        );

        ArgumentCaptor<BatchJobRuntimeControlRequest> commandCaptor =
                ArgumentCaptor.forClass(BatchJobRuntimeControlRequest.class);
        verify(stockBatchAdminClient).updateBatchJobRuntimeControl(
                org.mockito.ArgumentMatchers.eq("auto-market"),
                commandCaptor.capture()
        );
        BatchJobRuntimeControlRequest command = commandCaptor.getValue();
        assertThat(command.runtimeEnabled()).isFalse();
        assertThat(command.updatedBy()).isEqualTo("admin-user-key");
        assertThat(response.getData()).isEqualTo(batchResponse);
    }

    @Test
    void updateMarketStatus_orderBookClosed_runsSymbolMarketCloseRollover() {
        MarketStatusUpdateRequest request = new MarketStatusUpdateRequest(true, MarketSessionStatus.CLOSED);
        SymbolMarketConfigResponse marketResponse = new SymbolMarketConfigResponse(
                "MC001",
                true,
                MarketSessionStatus.CLOSED
        );
        when(marketService.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request))
                .thenReturn(marketResponse);

        var response = marketController.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request);

        verify(stockBatchAdminClient).runMarketCloseRollover("MC001");
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
        when(marketService.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request))
                .thenReturn(marketResponse);

        var response = marketController.updateMarketStatus(MarketType.ORDER_BOOK, "mc001", request);

        verify(stockBatchAdminClient, never()).runMarketCloseRollover("MC001");
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
        when(marketService.updateMarketStatus(MarketType.VIRTUAL_PRICE, "mc001", request))
                .thenReturn(marketResponse);

        var response = marketController.updateMarketStatus(MarketType.VIRTUAL_PRICE, "mc001", request);

        verify(stockBatchAdminClient, never()).runMarketCloseRollover("MC001");
        assertThat(response.getData()).isEqualTo(marketResponse);
    }

    @Test
    void runMarketCloseRollover_delegatesToStockBatchClient() {
        StockBatchJobRunResponse batchResponse = new StockBatchJobRunResponse(
                "market-close-rollover",
                "COMPLETED",
                "price-limit-base",
                7,
                "Job completed",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        when(stockBatchAdminClient.runMarketCloseRollover()).thenReturn(batchResponse);

        var response = marketController.runMarketCloseRollover();

        verify(stockBatchAdminClient).runMarketCloseRollover();
        assertThat(response.getData()).isEqualTo(batchResponse);
    }
}
