package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.util.List;

public record InstitutionPortfolioPolicyUpdateRequest(
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
        Integer decisionIntervalMinutes,
        List<InstitutionSymbolPolicyUpdateRequest> mandates,
        String changeReason
) {

    public InstitutionPortfolioPolicyUpdateRequest {
        mandates = mandates == null ? List.of() : List.copyOf(mandates);
    }
}
