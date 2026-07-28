package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record InstitutionPortfolioScheduledPolicyResponse(
        long policyVersion,
        LocalDate effectiveBusinessDate,
        String displayName,
        String investmentStyle,
        BigDecimal baseStockAllocationRate,
        BigDecimal minStockAllocationRate,
        BigDecimal maxStockAllocationRate,
        BigDecimal primaryRegimeWeight,
        BigDecimal assetPreferenceSensitivity,
        BigDecimal volatilitySensitivity,
        BigDecimal entryThresholdRate,
        BigDecimal exitThresholdRate,
        BigDecimal dailyTurnoverLimitRate,
        BigDecimal maxDecisionTurnoverRate,
        int decisionIntervalMinutes,
        List<InstitutionSymbolPolicyResponse> mandates,
        String changeReason,
        String changedBy,
        LocalDateTime updatedAt
) {

    public InstitutionPortfolioScheduledPolicyResponse {
        mandates = mandates == null ? List.of() : List.copyOf(mandates);
    }
}
