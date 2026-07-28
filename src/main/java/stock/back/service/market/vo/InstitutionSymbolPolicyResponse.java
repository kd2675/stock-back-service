package stock.back.service.market.vo;

import java.math.BigDecimal;

public record InstitutionSymbolPolicyResponse(
        String symbol,
        BigDecimal baseSymbolWeight,
        BigDecimal minPortfolioAllocationRate,
        BigDecimal maxPortfolioAllocationRate,
        BigDecimal pricePressureSensitivity,
        BigDecimal momentumSensitivity,
        BigDecimal valueSensitivity,
        BigDecimal reportSensitivity,
        long referenceDailyVolume,
        BigDecimal dailyParticipationRate
) {
}
