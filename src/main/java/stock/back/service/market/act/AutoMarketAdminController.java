package stock.back.service.market.act;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import lombok.RequiredArgsConstructor;
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
import stock.back.service.market.biz.AutoParticipantOverviewCacheService;
import stock.back.service.market.biz.AutoParticipantCashAdjustmentService;
import stock.back.service.market.biz.AutoParticipantManagementService;
import stock.back.service.market.biz.AutoParticipantOverviewQueryService;
import stock.back.service.market.biz.AutoParticipantProfileConfigService;
import stock.back.service.market.biz.AutoParticipantSymbolConfigService;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.AutoParticipantActivityScope;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentResponse;
import stock.back.service.market.vo.AutoParticipantHoldingGroupResponse;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;
import stock.back.service.market.vo.AutoParticipantProfileOverviewResponse;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;
import stock.back.service.market.vo.ListingAutoAccountRequest;
import stock.back.service.market.vo.ListingAutoAccountResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
@RequirePrincipalRole(anyOf = {UserRole.ADMIN})
public class AutoMarketAdminController {

    private final AutoParticipantOverviewQueryService autoParticipantOverviewQueryService;
    private final AutoParticipantOverviewCacheService autoParticipantOverviewCacheService;
    private final AutoParticipantProfileConfigService autoParticipantProfileConfigService;
    private final AutoMarketConfigService autoMarketConfigService;
    private final AutoParticipantManagementService autoParticipantManagementService;
    private final AutoParticipantCashAdjustmentService autoParticipantCashAdjustmentService;
    private final AutoParticipantSymbolConfigService autoParticipantSymbolConfigService;

    @GetMapping("/auto-market/participants/overviews")
    public ResponseDataDTO<List<AutoParticipantOverviewResponse>> getAutoParticipantOverviews(
            @RequestParam(defaultValue = "true") boolean includeHoldings,
            @RequestParam(defaultValue = "") List<String> userKeys,
            @RequestParam(defaultValue = "RECENT_SIMULATION_DAY") AutoParticipantActivityScope activityScope
    ) {
        return ResponseDataDTO.of(autoParticipantOverviewCacheService.getAutoParticipantOverviews(includeHoldings, userKeys, activityScope));
    }

    @GetMapping("/auto-market/participants")
    public ResponseDataDTO<List<AutoParticipantResponse>> getAutoParticipants() {
        return ResponseDataDTO.of(autoParticipantOverviewQueryService.getAutoParticipants());
    }

    @GetMapping("/auto-market/participants/holdings")
    public ResponseDataDTO<List<AutoParticipantHoldingGroupResponse>> getAutoParticipantHoldings(
            @RequestParam(defaultValue = "") List<String> userKeys
    ) {
        return ResponseDataDTO.of(autoParticipantOverviewQueryService.getAutoParticipantHoldings(userKeys));
    }

    @GetMapping("/auto-market/participants/profile-overviews")
    public ResponseDataDTO<List<AutoParticipantProfileOverviewResponse>> getAutoParticipantProfileOverviews(
            @RequestParam(defaultValue = "RECENT_SIMULATION_DAY") AutoParticipantActivityScope activityScope,
            @RequestParam(defaultValue = "") List<String> profileTypes
    ) {
        return ResponseDataDTO.of(autoParticipantOverviewCacheService.getAutoParticipantProfileOverviews(activityScope, profileTypes));
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

    @PatchMapping("/auto-market/participants/{userKey}")
    public ResponseDataDTO<AutoParticipantResponse> upsertAutoParticipant(
            @PathVariable String userKey,
            @RequestBody AutoParticipantRequest request,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(autoParticipantManagementService.upsertAutoParticipant(userKey, request, userContext.getUserKey()));
    }

    @DeleteMapping("/auto-market/participants/{userKey}")
    public ResponseDataDTO<AutoParticipantResponse> withdrawAutoParticipant(@PathVariable String userKey) {
        return ResponseDataDTO.of(autoParticipantManagementService.withdrawAutoParticipant(userKey));
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
