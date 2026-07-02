package stock.back.service.market.act;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.market.biz.CorporateActionCommandService;
import stock.back.service.market.biz.CorporateActionQueryService;
import stock.back.service.market.biz.InstrumentReportService;
import stock.back.service.market.vo.CorporateActionEntitlementResponse;
import stock.back.service.market.vo.CorporateActionRequest;
import stock.back.service.market.vo.CorporateActionResponse;
import stock.back.service.market.vo.InstrumentReportRequest;
import stock.back.service.market.vo.InstrumentReportResponse;
import stock.back.service.market.vo.OrderBookInstrumentResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
public class MarketCorporateActionController {

    private final CorporateActionCommandService corporateActionCommandService;
    private final CorporateActionQueryService corporateActionQueryService;
    private final InstrumentReportService instrumentReportService;

    @PostMapping("/order-book-instruments/{symbol}/corporate-actions")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<OrderBookInstrumentResponse> applyCorporateAction(
            @PathVariable String symbol,
            @Valid @RequestBody CorporateActionRequest request
    ) {
        return ResponseDataDTO.of(corporateActionCommandService.applyCorporateAction(symbol, request));
    }

    @GetMapping("/order-book-instruments/{symbol}/corporate-actions")
    public ResponseDataDTO<List<CorporateActionResponse>> getCorporateActions(@PathVariable String symbol) {
        return ResponseDataDTO.of(corporateActionQueryService.getCorporateActions(symbol));
    }

    @GetMapping("/order-book-instruments/{symbol}/reports")
    public ResponseDataDTO<List<InstrumentReportResponse>> getInstrumentReports(@PathVariable String symbol) {
        return ResponseDataDTO.of(instrumentReportService.getInstrumentReports(symbol));
    }

    @GetMapping("/order-book-instruments/{symbol}/reports/latest")
    public ResponseDataDTO<InstrumentReportResponse> getLatestInstrumentReport(@PathVariable String symbol) {
        return ResponseDataDTO.of(instrumentReportService.getLatestInstrumentReport(symbol));
    }

    @PostMapping("/order-book-instruments/{symbol}/reports")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<InstrumentReportResponse> publishInstrumentReport(
            @PathVariable String symbol,
            @Valid @RequestBody InstrumentReportRequest request,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(instrumentReportService.publishInstrumentReport(symbol, request, userContext.getUserKey()));
    }

    @PatchMapping("/order-book-instruments/{symbol}/reports")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<InstrumentReportResponse> updateInstrumentReport(
            @PathVariable String symbol,
            @Valid @RequestBody InstrumentReportRequest request,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(instrumentReportService.updateInstrumentReport(symbol, request, userContext.getUserKey()));
    }

    @DeleteMapping("/order-book-instruments/{symbol}/reports")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<InstrumentReportResponse> deleteInstrumentReport(
            @PathVariable String symbol,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(instrumentReportService.deleteInstrumentReport(symbol, userContext.getUserKey()));
    }

    @GetMapping("/corporate-action-entitlements/me")
    @RequirePrincipalRole
    public ResponseDataDTO<List<CorporateActionEntitlementResponse>> getMyCorporateActionEntitlements(UserContext userContext) {
        return ResponseDataDTO.of(corporateActionQueryService.getMyCorporateActionEntitlements(userContext.getUserKey()));
    }
}
