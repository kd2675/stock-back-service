package stock.back.service.market.biz;

record AutoMarketStatusQueryOptions(
        boolean includeConfigs,
        boolean includeParticipants,
        boolean includeParticipantSymbolConfigs,
        boolean includeParticipantProfileConfigs,
        boolean includeRuntimeMetrics,
        boolean includeSalaryEligibility,
        String participantSymbolConfigUserKey
) {

    static AutoMarketStatusQueryOptions of(
            boolean includeConfigs,
            boolean includeParticipants,
            boolean includeParticipantSymbolConfigs,
            boolean includeParticipantProfileConfigs,
            boolean includeRuntimeMetrics,
            boolean includeSalaryEligibility,
            String participantSymbolConfigUserKey
    ) {
        return new AutoMarketStatusQueryOptions(
                includeConfigs,
                includeParticipants,
                includeParticipantSymbolConfigs,
                includeParticipantProfileConfigs,
                includeRuntimeMetrics,
                includeSalaryEligibility,
                MarketTextNormalizer.optionalText(participantSymbolConfigUserKey)
        );
    }

    boolean shouldLoadConfigs() {
        return includeConfigs || includeParticipantSymbolConfigs;
    }

    boolean shouldLoadParticipants() {
        return includeParticipants || (includeParticipantSymbolConfigs && participantSymbolConfigUserKey == null);
    }

    boolean summaryOnly() {
        return !shouldLoadConfigs()
                && !shouldLoadParticipants()
                && !includeParticipantSymbolConfigs
                && !includeParticipantProfileConfigs
                && participantSymbolConfigUserKey == null;
    }

}
