package stock.back.service.market.vo;

public record AutoMarketDistributionBiasResponse(
        int pricePressure,
        int assetPreferencePressure,
        int volatilityPressure,
        int liquidityPressure,
        int executionAggressionPressure
) {
}
