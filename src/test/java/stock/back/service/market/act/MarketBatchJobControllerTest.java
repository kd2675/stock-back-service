package stock.back.service.market.act;

import auth.common.core.context.UserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import stock.back.service.market.biz.BatchJobRuntimeControlService;
import stock.back.service.market.biz.BatchJobSignalService;
import stock.back.service.market.biz.EodOperationsCommandService;
import stock.back.service.market.biz.EodOperationsOverviewService;
import stock.back.service.market.vo.AutoParticipantCashFlowControlRequest;
import stock.back.service.market.vo.AutoParticipantCashFlowStatusResponse;
import stock.back.service.market.vo.BatchJobRuntimeControlRequest;
import stock.back.service.market.vo.BatchJobRuntimeStatusResponse;
import stock.back.service.market.vo.EodPhaseRetryResponse;
import stock.back.service.market.vo.StockBatchJobRunResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketBatchJobControllerTest {

    private final BatchJobRuntimeControlService batchJobRuntimeControlService = mock(BatchJobRuntimeControlService.class);
    private final BatchJobSignalService batchJobSignalService = mock(BatchJobSignalService.class);
    private final EodOperationsCommandService eodOperationsCommandService = mock(EodOperationsCommandService.class);
    private final EodOperationsOverviewService eodOperationsOverviewService = mock(EodOperationsOverviewService.class);
    private final MarketBatchJobController marketBatchJobController = new MarketBatchJobController(
            batchJobRuntimeControlService,
            batchJobSignalService,
            eodOperationsCommandService,
            eodOperationsOverviewService
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
        when(batchJobRuntimeControlService.updateCashFlowStatus(false, "admin-user-key")).thenReturn(batchResponse);

        var response = marketBatchJobController.updateAutoParticipantCashFlowStatus(
                new AutoParticipantCashFlowControlRequest(false, "client-forged-user"),
                UserContext.builder()
                        .userKey("admin-user-key")
                        .role("ROLE_ADMIN")
                        .build()
        );

        verify(batchJobRuntimeControlService).updateCashFlowStatus(false, "admin-user-key");
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
        when(batchJobRuntimeControlService.update(
                org.mockito.ArgumentMatchers.eq("auto-market"),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("admin-user-key")
        )).thenReturn(batchResponse);

        var response = marketBatchJobController.updateBatchJobRuntimeControl(
                "auto-market",
                new BatchJobRuntimeControlRequest(false, "client-forged-user"),
                UserContext.builder()
                        .userKey("admin-user-key")
                        .role("ROLE_ADMIN")
                        .build()
        );

        verify(batchJobRuntimeControlService).update(
                org.mockito.ArgumentMatchers.eq("auto-market"),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("admin-user-key")
        );
        assertThat(response.getData()).isEqualTo(batchResponse);
    }

    @Test
    void runMarketCloseRollover_enqueuesBatchSignal() {
        StockBatchJobRunResponse batchResponse = new StockBatchJobRunResponse(
                "market-close-rollover",
                "QUEUED",
                "manual-rollover",
                0,
                "Batch job signal queued: id=1",
                LocalDateTime.now(),
                null
        );
        when(batchJobSignalService.enqueueMarketCloseRollover("admin-user-key")).thenReturn(batchResponse);

        var response = marketBatchJobController.runMarketCloseRollover(UserContext.builder()
                .userKey("admin-user-key")
                .role("ROLE_ADMIN")
                .build());

        verify(batchJobSignalService).enqueueMarketCloseRollover("admin-user-key");
        assertThat(response.getData()).isEqualTo(batchResponse);
    }

    @Test
    void getLatestAutoParticipantCashFlowRun_returnsLatestSignal() {
        StockBatchJobRunResponse batchResponse = new StockBatchJobRunResponse(
                "auto-participant-cash-flow",
                "COMPLETED",
                "manual-recurring-cash",
                12,
                "Job completed",
                LocalDateTime.now().minusSeconds(1),
                LocalDateTime.now()
        );
        when(batchJobSignalService.latestAutoParticipantCashFlow()).thenReturn(Optional.of(batchResponse));

        var response = marketBatchJobController.getLatestAutoParticipantCashFlowRun();

        verify(batchJobSignalService).latestAutoParticipantCashFlow();
        assertThat(response.getData()).isEqualTo(batchResponse);
    }

    @Test
    void retryFailedEodPhase_usesAuthenticatedAdminIdentity() {
        EodPhaseRetryResponse retryResponse = new EodPhaseRetryResponse(
                77L,
                LocalDate.of(2026, 7, 15),
                "LEDGER_FROZEN",
                "FAILED",
                "PENDING",
                2,
                "admin-user-key",
                LocalDateTime.now()
        );
        when(eodOperationsCommandService.retryFailedPhase(77L, "admin-user-key"))
                .thenReturn(retryResponse);

        var response = marketBatchJobController.retryFailedEodPhase(
                77L,
                UserContext.builder()
                        .userKey("admin-user-key")
                        .role("ROLE_ADMIN")
                        .build()
        );

        verify(eodOperationsCommandService).retryFailedPhase(77L, "admin-user-key");
        assertThat(response.getData()).isEqualTo(retryResponse);
    }
}
