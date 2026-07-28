package stock.back.service.market.vo;

import java.math.BigDecimal;

public record InstitutionSymbolPolicyUpdateRequest(
        String symbol,
        BigDecimal baseSymbolWeight,
        BigDecimal minPortfolioAllocationRate,
        BigDecimal maxPortfolioAllocationRate,
        BigDecimal pricePressureSensitivity,
        BigDecimal momentumSensitivity,
        BigDecimal valueSensitivity,
        BigDecimal reportSensitivity,
        Long referenceDailyVolume,
        BigDecimal dailyParticipationRate
) {
}
