package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.util.List;

public record InstitutionPortfolioRecommendationResponse(
        int activeSymbolCount,
        long currentPortfolioCount,
        int recommendedPortfolioCount,
        long recommendedRemainingCount,
        BigDecimal totalMarketCapitalization,
        BigDecimal recommendedAumRateOfMarketCap,
        BigDecimal minAumRateOfMarketCap,
        BigDecimal maxAumRateOfMarketCap,
        BigDecimal recommendedAumAmountPerPortfolio,
        List<Style> styles,
        List<Symbol> symbols
) {

    public InstitutionPortfolioRecommendationResponse {
        styles = styles == null ? List.of() : List.copyOf(styles);
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }

    public record Style(
            String investmentStyle,
            String label,
            String description,
            boolean recommended,
            BigDecimal recommendedAumRateOfMarketCap,
            BigDecimal recommendedAumAmountPerPortfolio,
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
            BigDecimal pricePressureSensitivity,
            BigDecimal momentumSensitivity,
            BigDecimal valueSensitivity,
            BigDecimal reportSensitivity,
            BigDecimal dailyParticipationRate
    ) {
    }

    public record Symbol(
            String symbol,
            String name,
            long tradableShares,
            BigDecimal currentPrice,
            BigDecimal marketWeight,
            long recommendedReferenceDailyVolume
    ) {
    }
}
