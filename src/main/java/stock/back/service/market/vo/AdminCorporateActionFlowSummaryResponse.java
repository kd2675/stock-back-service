package stock.back.service.market.vo;

public record AdminCorporateActionFlowSummaryResponse(
        long announcedCount,
        long exRightsAppliedCount,
        long paidCount,
        long listedCount,
        long delistedCount,
        long pendingCount,
        long todayCreatedCount
) {
}
