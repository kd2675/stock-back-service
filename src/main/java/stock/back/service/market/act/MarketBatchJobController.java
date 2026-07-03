package stock.back.service.market.act;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.common.exception.StockException;
import stock.back.service.market.biz.BatchJobRuntimeControlService;
import stock.back.service.market.biz.BatchJobSignalService;
import stock.back.service.market.vo.AutoParticipantCashFlowControlRequest;
import stock.back.service.market.vo.AutoParticipantCashFlowStatusResponse;
import stock.back.service.market.vo.BatchJobRuntimeControlRequest;
import stock.back.service.market.vo.BatchJobRuntimeStatusResponse;
import stock.back.service.market.vo.StockBatchJobRunResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
public class MarketBatchJobController {

    private final BatchJobRuntimeControlService batchJobRuntimeControlService;
    private final BatchJobSignalService batchJobSignalService;

    @GetMapping("/auto-market/cash-flow")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoParticipantCashFlowStatusResponse> getAutoParticipantCashFlowStatus() {
        return ResponseDataDTO.of(batchJobRuntimeControlService.cashFlowStatus());
    }

    @PatchMapping("/auto-market/cash-flow")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoParticipantCashFlowStatusResponse> updateAutoParticipantCashFlowStatus(
            @RequestBody AutoParticipantCashFlowControlRequest request,
            UserContext userContext
    ) {
        AutoParticipantCashFlowControlRequest command = new AutoParticipantCashFlowControlRequest(
                requireRuntimeEnabled(request == null ? null : request.runtimeEnabled()),
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(batchJobRuntimeControlService.updateCashFlowStatus(
                command.runtimeEnabled(),
                command.updatedBy()
        ));
    }

    @PostMapping("/auto-market/cash-flow/run")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<StockBatchJobRunResponse> runAutoParticipantCashFlow(UserContext userContext) {
        return ResponseDataDTO.of(batchJobSignalService.enqueueAutoParticipantCashFlow(userContext.getUserKey()));
    }

    @PostMapping("/batch-jobs/market-close/rollover")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<StockBatchJobRunResponse> runMarketCloseRollover(UserContext userContext) {
        return ResponseDataDTO.of(batchJobSignalService.enqueueMarketCloseRollover(userContext.getUserKey()));
    }

    @GetMapping("/batch-jobs/runtime-controls")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<List<BatchJobRuntimeStatusResponse>> getBatchJobRuntimeControls() {
        return ResponseDataDTO.of(batchJobRuntimeControlService.statuses());
    }

    @PatchMapping("/batch-jobs/runtime-controls/{jobName}")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<BatchJobRuntimeStatusResponse> updateBatchJobRuntimeControl(
            @PathVariable String jobName,
            @RequestBody BatchJobRuntimeControlRequest request,
            UserContext userContext
    ) {
        BatchJobRuntimeControlRequest command = new BatchJobRuntimeControlRequest(
                requireRuntimeEnabled(request == null ? null : request.runtimeEnabled()),
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(batchJobRuntimeControlService.update(
                jobName,
                command.runtimeEnabled(),
                command.updatedBy()
        ));
    }

    private static boolean requireRuntimeEnabled(Boolean runtimeEnabled) {
        if (runtimeEnabled == null) {
            throw StockException.badRequest("runtimeEnabled is required");
        }
        return runtimeEnabled;
    }
}
