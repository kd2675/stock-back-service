package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.util.List;

public record UnderwritingContractRecommendationResponse(
        int recommendedUnderwriterOrganizationCount,
        long currentUnderwriterOrganizationCount,
        int recommendedAccountCountPerSymbol,
        long currentContractCount,
        long recommendedRemainingContractCount,
        BigDecimal recommendedSupplyRate,
        int recommendedSupplyDurationDays,
        List<Symbol> symbols
) {

    public UnderwritingContractRecommendationResponse {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }

    public record Symbol(
            String symbol,
            String instrumentName,
            long issuedShares,
            long tradableShares,
            long lockedShares,
            BigDecimal issuePrice,
            Long corporateActionId,
            Long floatCustodyAccountId,
            long floatCustodyAvailableQuantity,
            boolean existingContract,
            boolean creationEligible,
            String eligibilityReason
    ) {
    }
}
