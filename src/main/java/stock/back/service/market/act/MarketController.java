package stock.back.service.market.act;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.market.biz.MarketService;
import stock.back.service.market.client.StockBatchAdminClient;
import stock.back.service.market.stream.PriceStreamService;
import stock.back.service.market.vo.AutoParticipantCashFlowControlRequest;
import stock.back.service.market.vo.AutoParticipantCashFlowStatusResponse;
import stock.back.service.market.vo.AutoMarketStatusResponse;
import stock.back.service.market.vo.AdminCashFlowPageResponse;
import stock.back.service.market.vo.AdminFlowOverviewResponse;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentResponse;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;
import stock.back.service.market.vo.BatchJobRuntimeControlRequest;
import stock.back.service.market.vo.BatchJobRuntimeStatusResponse;
import stock.back.service.market.vo.CorporateActionEntitlementResponse;
import stock.back.service.market.vo.CorporateActionRequest;
import stock.back.service.market.vo.CorporateActionResponse;
import stock.back.service.market.vo.InstrumentResponse;
import stock.back.service.market.vo.InstrumentReportRequest;
import stock.back.service.market.vo.InstrumentReportResponse;
import stock.back.service.market.vo.ListingAutoAccountRequest;
import stock.back.service.market.vo.ListingAutoAccountResponse;
import stock.back.service.market.vo.MarketStatusUpdateRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;
import stock.back.service.market.vo.OrderBookMarketStatusResponse;
import stock.back.service.market.vo.OrderBookResponse;
import stock.back.service.market.vo.OrderBookCandleResponse;
import stock.back.service.market.vo.OrderBookRecentExecutionResponse;
import stock.back.service.market.vo.OrderBookTradeSummaryResponse;
import stock.back.service.market.vo.PriceResponse;
import stock.back.service.market.vo.PriceTickResponse;
import stock.back.service.market.vo.RankingResponse;
import stock.back.service.market.vo.SymbolMarketConfigResponse;
import stock.back.service.market.vo.VirtualMarketStatusResponse;
import stock.back.service.market.vo.StockBatchJobRunResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;
    private final PriceStreamService priceStreamService;
    private final StockBatchAdminClient stockBatchAdminClient;

    @GetMapping("/instruments")
    public ResponseDataDTO<List<InstrumentResponse>> getInstruments() {
        return ResponseDataDTO.of(marketService.getInstruments());
    }

    @GetMapping("/order-book-instruments")
    public ResponseDataDTO<List<OrderBookInstrumentResponse>> getOrderBookInstruments() {
        return ResponseDataDTO.of(marketService.getOrderBookInstruments());
    }

    @PostMapping("/order-book-instruments")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<OrderBookInstrumentResponse> createOrderBookInstrument(
            @Valid @RequestBody OrderBookInstrumentRequest request
    ) {
        return ResponseDataDTO.of(marketService.createOrderBookInstrument(request));
    }

    @PostMapping("/order-book-instruments/{symbol}/corporate-actions")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<OrderBookInstrumentResponse> applyCorporateAction(
            @PathVariable String symbol,
            @Valid @RequestBody CorporateActionRequest request
    ) {
        return ResponseDataDTO.of(marketService.applyCorporateAction(symbol, request));
    }

    @GetMapping("/order-book-instruments/{symbol}/corporate-actions")
    public ResponseDataDTO<List<CorporateActionResponse>> getCorporateActions(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketService.getCorporateActions(symbol));
    }

    @GetMapping("/order-book-instruments/{symbol}/reports")
    public ResponseDataDTO<List<InstrumentReportResponse>> getInstrumentReports(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketService.getInstrumentReports(symbol));
    }

    @GetMapping("/order-book-instruments/{symbol}/reports/latest")
    public ResponseDataDTO<InstrumentReportResponse> getLatestInstrumentReport(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketService.getLatestInstrumentReport(symbol));
    }

    @PostMapping("/order-book-instruments/{symbol}/reports")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<InstrumentReportResponse> publishInstrumentReport(
            @PathVariable String symbol,
            @Valid @RequestBody InstrumentReportRequest request,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(marketService.publishInstrumentReport(symbol, request, userContext.getUserKey()));
    }

    @PatchMapping("/order-book-instruments/{symbol}/reports")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<InstrumentReportResponse> updateInstrumentReport(
            @PathVariable String symbol,
            @Valid @RequestBody InstrumentReportRequest request,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(marketService.updateInstrumentReport(symbol, request, userContext.getUserKey()));
    }

    @DeleteMapping("/order-book-instruments/{symbol}/reports")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<InstrumentReportResponse> deleteInstrumentReport(
            @PathVariable String symbol,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(marketService.deleteInstrumentReport(symbol, userContext.getUserKey()));
    }

    @GetMapping("/corporate-action-entitlements/me")
    @RequirePrincipalRole
    public ResponseDataDTO<List<CorporateActionEntitlementResponse>> getMyCorporateActionEntitlements(UserContext userContext) {
        return ResponseDataDTO.of(marketService.getMyCorporateActionEntitlements(userContext.getUserKey()));
    }

    @PatchMapping("/{marketType}/symbols/{symbol}/status")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<SymbolMarketConfigResponse> updateMarketStatus(
            @PathVariable MarketType marketType,
            @PathVariable String symbol,
            @RequestBody MarketStatusUpdateRequest request
    ) {
        SymbolMarketConfigResponse response = marketService.updateMarketStatus(marketType, symbol, request);
        if (marketType == MarketType.ORDER_BOOK
                && request != null
                && request.marketStatus() == MarketSessionStatus.CLOSED) {
            stockBatchAdminClient.runMarketCloseRollover(response.symbol());
        }
        return ResponseDataDTO.of(response);
    }

    @GetMapping("/prices")
    public ResponseDataDTO<List<PriceResponse>> getPrices() {
        return ResponseDataDTO.of(marketService.getPrices());
    }

    @GetMapping(value = "/prices/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPrices() {
        return priceStreamService.connect();
    }

    @GetMapping("/prices/{symbol}/ticks")
    public ResponseDataDTO<List<PriceTickResponse>> getPriceTicks(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketService.getPriceTicks(symbol));
    }

    @GetMapping("/order-books/{symbol}")
    public ResponseDataDTO<OrderBookResponse> getOrderBook(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketService.getOrderBook(symbol));
    }

    @GetMapping("/order-books/{symbol}/trade-summary")
    public ResponseDataDTO<OrderBookTradeSummaryResponse> getOrderBookTradeSummary(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketService.getOrderBookTradeSummary(symbol));
    }

    @GetMapping("/order-books/{symbol}/executions/recent")
    public ResponseDataDTO<List<OrderBookRecentExecutionResponse>> getRecentOrderBookExecutions(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketService.getRecentOrderBookExecutions(symbol));
    }

    @GetMapping("/order-books/{symbol}/candles/{interval}")
    public ResponseDataDTO<List<OrderBookCandleResponse>> getOrderBookCandles(
            @PathVariable String symbol,
            @PathVariable String interval
    ) {
        return ResponseDataDTO.of(marketService.getOrderBookCandles(symbol, interval));
    }

    @GetMapping("/rankings")
    public ResponseDataDTO<List<RankingResponse>> getRankings() {
        return ResponseDataDTO.of(marketService.getRankings());
    }

    @GetMapping("/virtual-market")
    public ResponseDataDTO<VirtualMarketStatusResponse> getVirtualMarketStatus() {
        return ResponseDataDTO.of(marketService.getVirtualMarketStatus());
    }

    @GetMapping("/order-book-market")
    public ResponseDataDTO<OrderBookMarketStatusResponse> getOrderBookMarketStatus() {
        return ResponseDataDTO.of(marketService.getOrderBookMarketStatus());
    }

    @GetMapping("/auto-market")
    public ResponseDataDTO<AutoMarketStatusResponse> getAutoMarketStatus() {
        return ResponseDataDTO.of(marketService.getAutoMarketStatus());
    }

    @GetMapping("/admin/flow-overview")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AdminFlowOverviewResponse> getAdminFlowOverview() {
        return ResponseDataDTO.of(marketService.getAdminFlowOverview());
    }

    @GetMapping("/admin/cash-flows")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AdminCashFlowPageResponse> getAdminCashFlows(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseDataDTO.of(marketService.getAdminCashFlows(page, size));
    }

    @GetMapping("/auto-market/participants/overviews")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<List<AutoParticipantOverviewResponse>> getAutoParticipantOverviews() {
        return ResponseDataDTO.of(marketService.getAutoParticipantOverviews());
    }

    @GetMapping("/auto-market/cash-flow")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoParticipantCashFlowStatusResponse> getAutoParticipantCashFlowStatus() {
        return ResponseDataDTO.of(stockBatchAdminClient.getAutoParticipantCashFlowStatus());
    }

    @PatchMapping("/auto-market/cash-flow")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoParticipantCashFlowStatusResponse> updateAutoParticipantCashFlowStatus(
            @RequestBody AutoParticipantCashFlowControlRequest request,
            UserContext userContext
    ) {
        if (request == null || request.runtimeEnabled() == null) {
            throw StockException.badRequest("runtimeEnabled is required");
        }
        AutoParticipantCashFlowControlRequest command = new AutoParticipantCashFlowControlRequest(
                request.runtimeEnabled(),
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(stockBatchAdminClient.updateAutoParticipantCashFlowStatus(command));
    }

    @PostMapping("/auto-market/cash-flow/run")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<StockBatchJobRunResponse> runAutoParticipantCashFlow() {
        return ResponseDataDTO.of(stockBatchAdminClient.runAutoParticipantCashFlow());
    }

    @PostMapping("/batch-jobs/market-close/rollover")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<StockBatchJobRunResponse> runMarketCloseRollover() {
        return ResponseDataDTO.of(stockBatchAdminClient.runMarketCloseRollover());
    }

    @GetMapping("/batch-jobs/runtime-controls")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<List<BatchJobRuntimeStatusResponse>> getBatchJobRuntimeControls() {
        return ResponseDataDTO.of(stockBatchAdminClient.getBatchJobRuntimeControls());
    }

    @PatchMapping("/batch-jobs/runtime-controls/{jobName}")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<BatchJobRuntimeStatusResponse> updateBatchJobRuntimeControl(
            @PathVariable String jobName,
            @RequestBody BatchJobRuntimeControlRequest request,
            UserContext userContext
    ) {
        if (request == null || request.runtimeEnabled() == null) {
            throw StockException.badRequest("runtimeEnabled is required");
        }
        BatchJobRuntimeControlRequest command = new BatchJobRuntimeControlRequest(
                request.runtimeEnabled(),
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(stockBatchAdminClient.updateBatchJobRuntimeControl(jobName, command));
    }

    @PatchMapping("/auto-market/profile-configs/{profileType}")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoParticipantProfileConfigResponse> updateAutoParticipantProfileConfig(
            @PathVariable String profileType,
            @RequestBody AutoParticipantProfileConfigRequest request
    ) {
        return ResponseDataDTO.of(marketService.updateAutoParticipantProfileConfig(profileType, request));
    }

    @PatchMapping("/auto-market/listing-accounts/{symbol}")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<ListingAutoAccountResponse> updateListingAutoAccountConfig(
            @PathVariable String symbol,
            @RequestBody ListingAutoAccountRequest request
    ) {
        return ResponseDataDTO.of(marketService.updateListingAutoAccountConfig(symbol, request));
    }

    @PatchMapping("/auto-market/configs/{symbol}")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoMarketConfigResponse> updateAutoMarketConfig(
            @PathVariable String symbol,
            @RequestBody AutoMarketConfigUpdateRequest request
    ) {
        return ResponseDataDTO.of(marketService.updateAutoMarketConfig(symbol, request));
    }

    @PatchMapping("/auto-market/participants/{userKey}")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoParticipantResponse> upsertAutoParticipant(
            @PathVariable String userKey,
            @RequestBody AutoParticipantRequest request
    ) {
        return ResponseDataDTO.of(marketService.upsertAutoParticipant(userKey, request));
    }

    @DeleteMapping("/auto-market/participants/{userKey}")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoParticipantResponse> withdrawAutoParticipant(@PathVariable String userKey) {
        return ResponseDataDTO.of(marketService.withdrawAutoParticipant(userKey));
    }

    @PostMapping("/auto-market/participants/{userKey}/cash-adjustments")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoParticipantCashAdjustmentResponse> adjustAutoParticipantCash(
            @PathVariable String userKey,
            @RequestBody AutoParticipantCashAdjustmentRequest request,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(marketService.adjustAutoParticipantCash(userKey, request, userContext.getUserKey()));
    }

    @PatchMapping("/auto-market/participants/{userKey}/symbols/{symbol}")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AutoParticipantSymbolConfigResponse> updateAutoParticipantSymbolConfig(
            @PathVariable String userKey,
            @PathVariable String symbol,
            @RequestBody AutoParticipantSymbolConfigRequest request
    ) {
        return ResponseDataDTO.of(marketService.updateAutoParticipantSymbolConfig(userKey, symbol, request));
    }
}
