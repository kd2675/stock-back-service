package stock.back.service.market.vo;

import java.util.List;

public record AutoMarketStatusResponse(
        boolean enabled,
        long configCount,
        long participantCount,
        long participantProfileConfigCount,
        long listingAutoAccountCount,
        long enabledParticipantCount,
        long salaryEligibleParticipantCount,
        long openAutoOrderCount,
        long todayAutoExecutionCount,
        List<AutoMarketConfigResponse> configs,
        List<AutoParticipantResponse> participants,
        List<AutoParticipantSymbolConfigResponse> participantSymbolConfigs,
        List<AutoParticipantProfileConfigResponse> participantProfileConfigs,
        List<ListingAutoAccountResponse> listingAutoAccounts
) {
}
