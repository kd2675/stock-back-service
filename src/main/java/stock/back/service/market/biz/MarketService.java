package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.MarketType;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.AutoMarketStatusResponse;
import stock.back.service.market.vo.AdminCashFlowPageResponse;
import stock.back.service.market.vo.AdminFlowOverviewResponse;
import stock.back.service.market.vo.AdminFundFlowSummaryResponse;
import stock.back.service.market.vo.AdminSymbolFlowListResponse;
import stock.back.service.market.vo.AutoParticipantHoldingGroupResponse;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentResponse;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;
import stock.back.service.market.vo.AutoParticipantProfileOverviewResponse;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MarketService {

    private final OrderBookInstrumentCommandService orderBookInstrumentCommandService;
    private final MarketCatalogQueryService marketCatalogQueryService;
    private final InstrumentReportService instrumentReportService;
    private final AutoParticipantCashAdjustmentService autoParticipantCashAdjustmentService;
    private final AutoParticipantManagementService autoParticipantManagementService;
    private final AutoParticipantProfileConfigService autoParticipantProfileConfigService;
    private final AutoParticipantSymbolConfigService autoParticipantSymbolConfigService;
    private final AutoMarketConfigService autoMarketConfigService;
    private final MarketStatusService marketStatusService;
    private final CorporateActionCommandService corporateActionCommandService;
    private final CorporateActionQueryService corporateActionQueryService;
    private final AdminFlowQueryService adminFlowQueryService;
    private final AutoParticipantOverviewQueryService autoParticipantOverviewQueryService;
    private final AutoMarketStatusQueryService autoMarketStatusQueryService;
    private final OrderBookMarketStatusQueryService orderBookMarketStatusQueryService;
    private final OrderBookQueryService orderBookQueryService;
    private final OrderBookCandleQueryService orderBookCandleQueryService;

    @Transactional(readOnly = true)
    public List<InstrumentResponse> getInstruments() {
        return marketCatalogQueryService.getInstruments();
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> getPrices() {
        return marketCatalogQueryService.getPrices();
    }

    @Transactional(readOnly = true)
    public List<OrderBookInstrumentResponse> getOrderBookInstruments() {
        return marketCatalogQueryService.getOrderBookInstruments();
    }

    @Transactional
    public OrderBookInstrumentResponse createOrderBookInstrument(OrderBookInstrumentRequest request) {
        return orderBookInstrumentCommandService.createOrderBookInstrument(request);
    }

    @Transactional
    public OrderBookInstrumentResponse applyCorporateAction(String symbol, CorporateActionRequest request) {
        return corporateActionCommandService.applyCorporateAction(symbol, request);
    }

    @Transactional(readOnly = true)
    public List<CorporateActionResponse> getCorporateActions(String symbol) {
        return corporateActionQueryService.getCorporateActions(symbol);
    }

    @Transactional(readOnly = true)
    public List<InstrumentReportResponse> getInstrumentReports(String symbol) {
        return instrumentReportService.getInstrumentReports(symbol);
    }

    @Transactional(readOnly = true)
    public InstrumentReportResponse getLatestInstrumentReport(String symbol) {
        return instrumentReportService.getLatestInstrumentReport(symbol);
    }

    @Transactional
    public InstrumentReportResponse publishInstrumentReport(String symbol, InstrumentReportRequest request, String createdBy) {
        return instrumentReportService.publishInstrumentReport(symbol, request, createdBy);
    }

    @Transactional
    public InstrumentReportResponse updateInstrumentReport(String symbol, InstrumentReportRequest request, String createdBy) {
        return instrumentReportService.updateInstrumentReport(symbol, request, createdBy);
    }

    @Transactional
    public InstrumentReportResponse deleteInstrumentReport(String symbol, String createdBy) {
        return instrumentReportService.deleteInstrumentReport(symbol, createdBy);
    }

    @Transactional(readOnly = true)
    public List<CorporateActionEntitlementResponse> getMyCorporateActionEntitlements(String userKey) {
        return corporateActionQueryService.getMyCorporateActionEntitlements(userKey);
    }

    @Transactional
    public SymbolMarketConfigResponse updateMarketStatus(MarketType marketType, String symbol, MarketStatusUpdateRequest request) {
        return marketStatusService.updateMarketStatus(marketType, symbol, request);
    }

    @Transactional(readOnly = true)
    public List<RankingResponse> getRankings() {
        return marketCatalogQueryService.getRankings();
    }

    @Transactional(readOnly = true)
    public List<PriceTickResponse> getPriceTicks(String symbol) {
        return marketCatalogQueryService.getPriceTicks(symbol);
    }

    @Transactional(readOnly = true)
    public OrderBookResponse getOrderBook(String symbol) {
        return orderBookQueryService.getOrderBook(symbol);
    }

    @Transactional(readOnly = true)
    public OrderBookTradeSummaryResponse getOrderBookTradeSummary(String symbol) {
        return orderBookQueryService.getOrderBookTradeSummary(symbol);
    }

    @Transactional(readOnly = true)
    public List<OrderBookRecentExecutionResponse> getRecentOrderBookExecutions(String symbol) {
        return orderBookQueryService.getRecentOrderBookExecutions(symbol);
    }

    @Transactional(readOnly = true)
    public List<OrderBookCandleResponse> getOrderBookCandles(String symbol, String interval) {
        return orderBookCandleQueryService.getOrderBookCandles(symbol, interval);
    }

    @Transactional(readOnly = true)
    public VirtualMarketStatusResponse getVirtualMarketStatus() {
        return marketStatusService.getVirtualMarketStatus();
    }

    @Transactional(readOnly = true)
    public OrderBookMarketStatusResponse getOrderBookMarketStatus() {
        return orderBookMarketStatusQueryService.getOrderBookMarketStatus();
    }

    @Transactional(readOnly = true)
    public OrderBookMarketStatusResponse getOrderBookMarketStatus(boolean includeConfigs) {
        return orderBookMarketStatusQueryService.getOrderBookMarketStatus(includeConfigs);
    }

    @Transactional(readOnly = true)
    public OrderBookMarketStatusResponse getOrderBookMarketStatus(boolean includeConfigs, boolean includeTodayExecution) {
        return orderBookMarketStatusQueryService.getOrderBookMarketStatus(includeConfigs, includeTodayExecution);
    }

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview() {
        return adminFlowQueryService.getAdminFlowOverview();
    }

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview(int symbolFlowLimit) {
        return adminFlowQueryService.getAdminFlowOverview(symbolFlowLimit);
    }

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview(int symbolFlowLimit, boolean includeFundFlow) {
        return adminFlowQueryService.getAdminFlowOverview(symbolFlowLimit, includeFundFlow);
    }

    @Transactional(readOnly = true)
    public AdminFlowOverviewResponse getAdminFlowOverview(int symbolFlowLimit, boolean includeFundFlow, boolean includeSymbolFlows) {
        return adminFlowQueryService.getAdminFlowOverview(symbolFlowLimit, includeFundFlow, includeSymbolFlows);
    }

    @Transactional(readOnly = true)
    public AdminFundFlowSummaryResponse getAdminFundFlowSummary() {
        return adminFlowQueryService.getAdminFundFlowSummary();
    }

    @Transactional(readOnly = true)
    public AdminSymbolFlowListResponse getAdminSymbolFlows(int symbolFlowLimit) {
        return adminFlowQueryService.getAdminSymbolFlows(symbolFlowLimit);
    }

    @Transactional(readOnly = true)
    public AdminCashFlowPageResponse getAdminCashFlows(int page, int size) {
        return adminFlowQueryService.getAdminCashFlows(page, size);
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus() {
        return autoMarketStatusQueryService.getAutoMarketStatus();
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus(boolean includeParticipantSymbolConfigs) {
        return autoMarketStatusQueryService.getAutoMarketStatus(includeParticipantSymbolConfigs);
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus(
            boolean includeConfigs,
            boolean includeParticipants,
            boolean includeParticipantSymbolConfigs,
            boolean includeParticipantProfileConfigs,
            boolean includeListingAutoAccounts,
            boolean includeRuntimeMetrics
    ) {
        return autoMarketStatusQueryService.getAutoMarketStatus(
                includeConfigs,
                includeParticipants,
                includeParticipantSymbolConfigs,
                includeParticipantProfileConfigs,
                includeListingAutoAccounts,
                includeRuntimeMetrics,
                true,
                null
        );
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus(
            boolean includeConfigs,
            boolean includeParticipants,
            boolean includeParticipantSymbolConfigs,
            boolean includeParticipantProfileConfigs,
            boolean includeListingAutoAccounts,
            boolean includeRuntimeMetrics,
            boolean includeSalaryEligibility,
            String participantSymbolConfigUserKey
    ) {
        return autoMarketStatusQueryService.getAutoMarketStatus(
                includeConfigs,
                includeParticipants,
                includeParticipantSymbolConfigs,
                includeParticipantProfileConfigs,
                includeListingAutoAccounts,
                includeRuntimeMetrics,
                includeSalaryEligibility,
                participantSymbolConfigUserKey
        );
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantProfileConfigResponse> getAutoParticipantProfileConfigs() {
        return autoMarketStatusQueryService.getAutoParticipantProfileConfigs();
    }

    @Transactional
    public AutoParticipantProfileConfigResponse updateAutoParticipantProfileConfig(
            String profileTypeValue,
            AutoParticipantProfileConfigRequest request
    ) {
        return autoParticipantProfileConfigService.updateAutoParticipantProfileConfig(profileTypeValue, request);
    }

    @Transactional
    public ListingAutoAccountResponse updateListingAutoAccountConfig(String symbol, ListingAutoAccountRequest request) {
        return autoMarketConfigService.updateListingAutoAccountConfig(symbol, request);
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantOverviewResponse> getAutoParticipantOverviews() {
        return getAutoParticipantOverviews(true, List.of());
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantOverviewResponse> getAutoParticipantOverviews(boolean includeHoldings) {
        return getAutoParticipantOverviews(includeHoldings, List.of());
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantProfileOverviewResponse> getAutoParticipantProfileOverviews() {
        return autoParticipantOverviewQueryService.getAutoParticipantProfileOverviews();
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantOverviewResponse> getAutoParticipantOverviews(boolean includeHoldings, List<String> userKeys) {
        return autoParticipantOverviewQueryService.getAutoParticipantOverviews(includeHoldings, userKeys);
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantHoldingGroupResponse> getAutoParticipantHoldings(List<String> userKeys) {
        return autoParticipantOverviewQueryService.getAutoParticipantHoldings(userKeys);
    }

    @Transactional
    public AutoMarketConfigResponse updateAutoMarketConfig(String symbol, AutoMarketConfigUpdateRequest request) {
        return autoMarketConfigService.updateAutoMarketConfig(symbol, request);
    }

    @Transactional
    public AutoParticipantResponse upsertAutoParticipant(String userKey, AutoParticipantRequest request) {
        return autoParticipantManagementService.upsertAutoParticipant(userKey, request);
    }

    @Transactional
    public AutoParticipantCashAdjustmentResponse adjustAutoParticipantCash(
            String userKey,
            AutoParticipantCashAdjustmentRequest request,
            String adminUserKey
    ) {
        return autoParticipantCashAdjustmentService.adjustAutoParticipantCash(userKey, request, adminUserKey);
    }

    @Transactional
    public AutoParticipantResponse withdrawAutoParticipant(String userKey) {
        return autoParticipantManagementService.withdrawAutoParticipant(userKey);
    }

    @Transactional
    public AutoParticipantSymbolConfigResponse updateAutoParticipantSymbolConfig(
            String userKey,
            String symbol,
            AutoParticipantSymbolConfigRequest request
    ) {
        return autoParticipantSymbolConfigService.updateAutoParticipantSymbolConfig(userKey, symbol, request);
    }

}
