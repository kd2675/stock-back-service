package stock.back.service.market.act;

import java.time.LocalDate;
import java.util.List;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.market.biz.AutoMarketConfigService;
import stock.back.service.market.biz.AutoMarketRegimeHistoryQueryService;
import stock.back.service.market.biz.AutoParticipantOverviewCacheService;
import stock.back.service.market.biz.AutoParticipantPerformanceSummaryQueryService;
import stock.back.service.market.biz.AutoParticipantCashAdjustmentService;
import stock.back.service.market.biz.AutoParticipantManagementService;
import stock.back.service.market.biz.AutoParticipantOverviewQueryService;
import stock.back.service.market.biz.AutoParticipantProfileConfigService;
import stock.back.service.market.biz.AutoParticipantSymbolConfigQueryService;
import stock.back.service.market.biz.AutoParticipantSymbolConfigService;
import stock.back.service.market.biz.AutoParticipantWithdrawalQueryService;
import stock.back.service.market.biz.InstitutionEmergencyStopService;
import stock.back.service.market.biz.InstitutionPortfolioQueryService;
import stock.back.service.market.biz.InstitutionPortfolioProvisionService;
import stock.back.service.market.biz.InstitutionPortfolioRecommendationService;
import stock.back.service.market.biz.LiquidityProviderMandateQueryService;
import stock.back.service.market.biz.LiquidityProviderRecommendationService;
import stock.back.service.market.biz.LiquidityProviderTransitionService;
import stock.back.service.market.biz.SystemCustodyQueryService;
import stock.back.service.market.biz.UnderwritingContractQueryService;
import stock.back.service.market.biz.UnderwritingContractProvisionService;
import stock.back.service.market.biz.UnderwritingContractRecommendationService;
import stock.back.service.market.biz.UnderwritingSupplyTransitionService;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.AutoMarketRegimeHistoryRangeResponse;
import stock.back.service.market.vo.AutoMarketRegimeHistoryResponse;
import stock.back.service.market.vo.AutoParticipantActivityScope;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentResponse;
import stock.back.service.market.vo.AutoParticipantHoldingGroupResponse;
import stock.back.service.market.vo.AutoParticipantLifecycleScope;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;
import stock.back.service.market.vo.AutoParticipantPerformanceBasis;
import stock.back.service.market.vo.AutoParticipantPerformanceSummaryResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;
import stock.back.service.market.vo.AutoParticipantProfileOverviewResponse;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;
import stock.back.service.market.vo.AutoParticipantWithdrawalAuditResponse;
import stock.back.service.market.vo.ListingAutoAccountRequest;
import stock.back.service.market.vo.ListingAutoAccountResponse;
import stock.back.service.market.vo.InstitutionPortfolioResponse;
import stock.back.service.market.vo.InstitutionPortfolioCreateRequest;
import stock.back.service.market.vo.InstitutionPortfolioRecommendationResponse;
import stock.back.service.market.vo.InstitutionSuspensionRequest;
import stock.back.service.market.vo.LiquidityProviderMandateResponse;
import stock.back.service.market.vo.LiquidityProviderRecommendationResponse;
import stock.back.service.market.vo.LiquidityProviderProvisionRequest;
import stock.back.service.market.vo.SystemCustodyOverviewResponse;
import stock.back.service.market.vo.UnderwritingContractResponse;
import stock.back.service.market.vo.UnderwritingContractCreateRequest;
import stock.back.service.market.vo.UnderwritingContractRecommendationResponse;
import stock.back.service.market.vo.UnderwritingSupplyActivationRequest;
import stock.back.service.market.vo.UnderwritingSupplySuspensionRequest;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
@RequirePrincipalRole(anyOf = {UserRole.ADMIN})
public class AutoMarketAdminController {

    private final AutoParticipantOverviewQueryService autoParticipantOverviewQueryService;
    private final AutoParticipantOverviewCacheService autoParticipantOverviewCacheService;
    private final AutoParticipantProfileConfigService autoParticipantProfileConfigService;
    private final AutoMarketConfigService autoMarketConfigService;
    private final AutoMarketRegimeHistoryQueryService autoMarketRegimeHistoryQueryService;
    private final AutoParticipantManagementService autoParticipantManagementService;
    private final AutoParticipantCashAdjustmentService autoParticipantCashAdjustmentService;
    private final AutoParticipantSymbolConfigService autoParticipantSymbolConfigService;
    private final AutoParticipantSymbolConfigQueryService autoParticipantSymbolConfigQueryService;
    private final AutoParticipantPerformanceSummaryQueryService autoParticipantPerformanceSummaryQueryService;
    private final AutoParticipantWithdrawalQueryService autoParticipantWithdrawalQueryService;
    private final InstitutionPortfolioQueryService institutionPortfolioQueryService;
    private final InstitutionPortfolioProvisionService institutionPortfolioProvisionService;
    private final InstitutionPortfolioRecommendationService
            institutionPortfolioRecommendationService;
    private final InstitutionEmergencyStopService institutionEmergencyStopService;
    private final LiquidityProviderMandateQueryService liquidityProviderMandateQueryService;
    private final LiquidityProviderRecommendationService liquidityProviderRecommendationService;
    private final LiquidityProviderTransitionService liquidityProviderTransitionService;
    private final SystemCustodyQueryService systemCustodyQueryService;
    private final UnderwritingContractQueryService underwritingContractQueryService;
    private final UnderwritingContractProvisionService underwritingContractProvisionService;
    private final UnderwritingContractRecommendationService
            underwritingContractRecommendationService;
    private final UnderwritingSupplyTransitionService underwritingSupplyTransitionService;

    @GetMapping("/system-custody")
    public ResponseDataDTO<SystemCustodyOverviewResponse> getSystemCustodyOverview() {
        return ResponseDataDTO.of(systemCustodyQueryService.getOverview());
    }

    @GetMapping("/underwriting-contracts")
    public ResponseDataDTO<List<UnderwritingContractResponse>> getUnderwritingContracts() {
        return ResponseDataDTO.of(underwritingContractQueryService.getContracts());
    }

    @GetMapping("/underwriting-contracts/recommendations")
    public ResponseDataDTO<UnderwritingContractRecommendationResponse>
    getUnderwritingContractRecommendations() {
        return ResponseDataDTO.of(
                underwritingContractRecommendationService.getRecommendation()
        );
    }

    @PostMapping("/underwriting-contracts/{symbol}")
    public ResponseDataDTO<UnderwritingContractResponse> createUnderwritingContract(
            @PathVariable String symbol,
            @RequestBody(required = false) UnderwritingContractCreateRequest request,
            UserContext userContext
    ) {
        long contractId = underwritingContractProvisionService.createContract(
                symbol,
                request,
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(
                underwritingContractQueryService.getContract(contractId)
        );
    }

    @PostMapping("/underwriting-contracts/{contractId}/supply/activate")
    public ResponseDataDTO<UnderwritingContractResponse> activateUnderwritingSupply(
            @PathVariable long contractId,
            @RequestBody(required = false) UnderwritingSupplyActivationRequest request,
            UserContext userContext
    ) {
        underwritingSupplyTransitionService.activate(
                contractId,
                request,
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(underwritingContractQueryService.getContract(contractId));
    }

    @PostMapping("/underwriting-contracts/{contractId}/supply/suspend")
    public ResponseDataDTO<UnderwritingContractResponse> suspendUnderwritingSupply(
            @PathVariable long contractId,
            @RequestBody(required = false) UnderwritingSupplySuspensionRequest request,
            UserContext userContext
    ) {
        underwritingSupplyTransitionService.suspend(
                contractId,
                request,
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(underwritingContractQueryService.getContract(contractId));
    }

    @GetMapping("/liquidity-mandates")
    public ResponseDataDTO<List<LiquidityProviderMandateResponse>> getLiquidityMandates() {
        return ResponseDataDTO.of(liquidityProviderMandateQueryService.getMandates());
    }

    @GetMapping("/liquidity-mandates/recommendations")
    public ResponseDataDTO<LiquidityProviderRecommendationResponse>
    getLiquidityMandateRecommendations() {
        return ResponseDataDTO.of(
                liquidityProviderRecommendationService.getRecommendation()
        );
    }

    @PostMapping("/liquidity-mandates/{symbol}")
    public ResponseDataDTO<LiquidityProviderMandateResponse> createLiquidityProviderLive(
            @PathVariable String symbol,
            @RequestBody(required = false) LiquidityProviderProvisionRequest request,
            UserContext userContext
    ) {
        liquidityProviderTransitionService.provisionLive(
                symbol,
                request,
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(liquidityProviderMandateQueryService.getMandate(symbol));
    }

    @GetMapping("/institution-portfolios")
    public ResponseDataDTO<List<InstitutionPortfolioResponse>> getInstitutionPortfolios() {
        return ResponseDataDTO.of(institutionPortfolioQueryService.getPortfolios());
    }

    @GetMapping("/institution-portfolios/recommendations")
    public ResponseDataDTO<InstitutionPortfolioRecommendationResponse>
    getInstitutionPortfolioRecommendations() {
        return ResponseDataDTO.of(
                institutionPortfolioRecommendationService.getRecommendation()
        );
    }

    @PostMapping("/institution-portfolios")
    public ResponseDataDTO<InstitutionPortfolioResponse> createInstitutionPortfolio(
            @RequestBody InstitutionPortfolioCreateRequest request,
            UserContext userContext
    ) {
        long portfolioId = institutionPortfolioProvisionService.createPortfolio(
                request,
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(
                institutionPortfolioQueryService.getPortfolio(portfolioId)
        );
    }

    @PostMapping("/institution-portfolios/{portfolioId}/suspend")
    public ResponseDataDTO<List<InstitutionPortfolioResponse>> suspendInstitution(
            @PathVariable long portfolioId,
            @RequestBody(required = false) InstitutionSuspensionRequest request,
            UserContext userContext
    ) {
        institutionEmergencyStopService.suspend(
                portfolioId,
                request,
                userContext.getUserKey()
        );
        return ResponseDataDTO.of(institutionPortfolioQueryService.getPortfolios());
    }

    @GetMapping("/auto-market/participants/overviews")
    public ResponseDataDTO<List<AutoParticipantOverviewResponse>> getAutoParticipantOverviews(
            @RequestParam(defaultValue = "true") boolean includeHoldings,
            @RequestParam(defaultValue = "") List<String> userKeys,
            @RequestParam(defaultValue = "RECENT_SIMULATION_DAY") AutoParticipantActivityScope activityScope,
            @RequestParam(defaultValue = "CURRENT") AutoParticipantLifecycleScope lifecycleScope
    ) {
        return ResponseDataDTO.of(autoParticipantOverviewCacheService.getAutoParticipantOverviews(
                includeHoldings,
                userKeys,
                activityScope,
                lifecycleScope
        ));
    }

    @GetMapping("/auto-market/participants")
    public ResponseDataDTO<List<AutoParticipantResponse>> getAutoParticipants(
            @RequestParam(defaultValue = "CURRENT") AutoParticipantLifecycleScope lifecycleScope
    ) {
        return ResponseDataDTO.of(autoParticipantOverviewQueryService.getAutoParticipants(lifecycleScope));
    }

    @GetMapping("/auto-market/participants/symbol-configs")
    public ResponseDataDTO<List<AutoParticipantSymbolConfigResponse>> getAutoParticipantSymbolConfigs(
            @RequestParam(defaultValue = "CURRENT") AutoParticipantLifecycleScope lifecycleScope,
            @RequestParam(defaultValue = "") List<String> userKeys
    ) {
        return ResponseDataDTO.of(autoParticipantSymbolConfigQueryService.getAutoParticipantSymbolConfigs(
                lifecycleScope,
                userKeys
        ));
    }

    @GetMapping("/auto-market/participants/holdings")
    public ResponseDataDTO<List<AutoParticipantHoldingGroupResponse>> getAutoParticipantHoldings(
            @RequestParam(defaultValue = "") List<String> userKeys
    ) {
        return ResponseDataDTO.of(autoParticipantOverviewQueryService.getAutoParticipantHoldings(userKeys));
    }

    @GetMapping("/auto-market/participants/withdrawal-audits")
    public ResponseDataDTO<List<AutoParticipantWithdrawalAuditResponse>> getAutoParticipantWithdrawalAudits(
            @RequestParam(defaultValue = "") List<String> userKeys
    ) {
        return ResponseDataDTO.of(
                autoParticipantWithdrawalQueryService.getWithdrawalAudits(userKeys)
        );
    }

    @GetMapping("/auto-market/participants/profile-overviews")
    public ResponseDataDTO<List<AutoParticipantProfileOverviewResponse>> getAutoParticipantProfileOverviews(
            @RequestParam(defaultValue = "RECENT_SIMULATION_DAY") AutoParticipantActivityScope activityScope,
            @RequestParam(defaultValue = "") List<String> profileTypes
    ) {
        return ResponseDataDTO.of(autoParticipantOverviewCacheService.getAutoParticipantProfileOverviews(activityScope, profileTypes));
    }

    @GetMapping("/auto-market/participants/performance-summary")
    public ResponseDataDTO<AutoParticipantPerformanceSummaryResponse> getAutoParticipantPerformanceSummary(
            @RequestParam(defaultValue = "LIVE_ESTIMATE") AutoParticipantPerformanceBasis basis
    ) {
        return ResponseDataDTO.of(autoParticipantPerformanceSummaryQueryService.getPerformanceSummary(basis));
    }

    @PatchMapping("/auto-market/profile-configs/{profileType}")
    public ResponseDataDTO<AutoParticipantProfileConfigResponse> updateAutoParticipantProfileConfig(
            @PathVariable String profileType,
            @RequestBody AutoParticipantProfileConfigRequest request
    ) {
        return ResponseDataDTO.of(autoParticipantProfileConfigService.updateAutoParticipantProfileConfig(profileType, request));
    }

    @PatchMapping("/auto-market/listing-accounts/{symbol}")
    public ResponseDataDTO<ListingAutoAccountResponse> updateListingAutoAccountConfig(
            @PathVariable String symbol,
            @RequestBody ListingAutoAccountRequest request
    ) {
        return ResponseDataDTO.of(autoMarketConfigService.updateListingAutoAccountConfig(symbol, request));
    }

    @PatchMapping("/auto-market/configs/{symbol}")
    public ResponseDataDTO<AutoMarketConfigResponse> updateAutoMarketConfig(
            @PathVariable String symbol,
            @RequestBody AutoMarketConfigUpdateRequest request
    ) {
        return ResponseDataDTO.of(autoMarketConfigService.updateAutoMarketConfig(symbol, request));
    }

    @PostMapping("/auto-market/configs/{symbol}/daily-regime/regenerate")
    public ResponseDataDTO<AutoMarketConfigResponse> regenerateAutoMarketDailyRegime(@PathVariable String symbol) {
        return ResponseDataDTO.of(autoMarketConfigService.regenerateDailyRegime(symbol));
    }

    @PostMapping("/auto-market/configs/{symbol}/regime-modifier/regenerate")
    public ResponseDataDTO<AutoMarketConfigResponse> regenerateAutoMarketRegimeModifier(@PathVariable String symbol) {
        return ResponseDataDTO.of(autoMarketConfigService.regenerateRegimeModifier(symbol));
    }

    @GetMapping("/auto-market/configs/{symbol}/regime-history")
    public ResponseDataDTO<AutoMarketRegimeHistoryResponse> getAutoMarketRegimeHistory(
            @PathVariable String symbol,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate
    ) {
        return ResponseDataDTO.of(autoMarketRegimeHistoryQueryService.getHistory(symbol, tradeDate));
    }

    @GetMapping("/auto-market/configs/{symbol}/regime-history/range")
    public ResponseDataDTO<AutoMarketRegimeHistoryRangeResponse> getAutoMarketRegimeHistoryRange(
            @PathVariable String symbol,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseDataDTO.of(autoMarketRegimeHistoryQueryService.getHistoryRange(symbol, endDate));
    }

    @PatchMapping("/auto-market/participants/{userKey}")
    public ResponseDataDTO<AutoParticipantResponse> upsertAutoParticipant(
            @PathVariable String userKey,
            @RequestBody AutoParticipantRequest request,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(autoParticipantManagementService.upsertAutoParticipant(userKey, request, userContext.getUserKey()));
    }

    @DeleteMapping("/auto-market/participants/{userKey}")
    public ResponseDataDTO<AutoParticipantResponse> withdrawAutoParticipant(
            @PathVariable String userKey,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(
                autoParticipantManagementService.withdrawAutoParticipant(userKey, userContext.getUserKey())
        );
    }

    @PostMapping("/auto-market/participants/{userKey}/cash-adjustments")
    public ResponseDataDTO<AutoParticipantCashAdjustmentResponse> adjustAutoParticipantCash(
            @PathVariable String userKey,
            @RequestBody AutoParticipantCashAdjustmentRequest request,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(autoParticipantCashAdjustmentService.adjustAutoParticipantCash(userKey, request, userContext.getUserKey()));
    }

    @PatchMapping("/auto-market/participants/{userKey}/symbols/{symbol}")
    public ResponseDataDTO<AutoParticipantSymbolConfigResponse> updateAutoParticipantSymbolConfig(
            @PathVariable String userKey,
            @PathVariable String symbol,
            @RequestBody AutoParticipantSymbolConfigRequest request
    ) {
        return ResponseDataDTO.of(autoParticipantSymbolConfigService.updateAutoParticipantSymbolConfig(userKey, symbol, request));
    }
}
