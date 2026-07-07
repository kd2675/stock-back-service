package stock.back.service.market.act;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.market.biz.AdminFlowQueryService;
import stock.back.service.market.vo.AdminCashFlowPageResponse;
import stock.back.service.market.vo.AdminFlowOverviewResponse;
import stock.back.service.market.vo.AdminFundFlowScope;
import stock.back.service.market.vo.AdminFundFlowSummaryResponse;
import stock.back.service.market.vo.AdminSymbolFlowListResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
@RequirePrincipalRole(anyOf = {UserRole.ADMIN})
public class MarketAdminController {

    private final AdminFlowQueryService adminFlowQueryService;

    @GetMapping("/admin/flow-overview")
    public ResponseDataDTO<AdminFlowOverviewResponse> getAdminFlowOverview(
            @RequestParam(defaultValue = "0") int symbolFlowLimit,
            @RequestParam(defaultValue = "true") boolean includeFundFlow,
            @RequestParam(defaultValue = "true") boolean includeSymbolFlows,
            @RequestParam(defaultValue = "RECENT_SIMULATION_DAY") AdminFundFlowScope fundFlowScope,
            @RequestParam(defaultValue = "RECENT_SIMULATION_DAY") AdminFundFlowScope symbolFlowScope
    ) {
        return ResponseDataDTO.of(adminFlowQueryService.getAdminFlowOverview(
                symbolFlowLimit,
                includeFundFlow,
                includeSymbolFlows,
                fundFlowScope,
                symbolFlowScope
        ));
    }

    @GetMapping("/admin/fund-flow-summary")
    public ResponseDataDTO<AdminFundFlowSummaryResponse> getAdminFundFlowSummary(
            @RequestParam(defaultValue = "RECENT_SIMULATION_DAY") AdminFundFlowScope scope
    ) {
        return ResponseDataDTO.of(adminFlowQueryService.getAdminFundFlowSummary(scope));
    }

    @GetMapping("/admin/symbol-flows")
    public ResponseDataDTO<AdminSymbolFlowListResponse> getAdminSymbolFlows(
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(defaultValue = "RECENT_SIMULATION_DAY") AdminFundFlowScope scope,
            @RequestParam(defaultValue = "false") boolean includeDailyCumulative,
            @RequestParam(defaultValue = "7") int dailyCumulativeDays
    ) {
        return ResponseDataDTO.of(adminFlowQueryService.getAdminSymbolFlows(
                limit,
                scope,
                includeDailyCumulative,
                dailyCumulativeDays
        ));
    }

    @GetMapping("/admin/cash-flows")
    public ResponseDataDTO<AdminCashFlowPageResponse> getAdminCashFlows(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseDataDTO.of(adminFlowQueryService.getAdminCashFlows(page, size));
    }
}
