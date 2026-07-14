package stock.back.service.market.vo;

public record AutoMarketDistributionBiasRequest(
        Integer pricePressure,
        Integer assetPreferencePressure,
        Integer volatilityPressure,
        Integer liquidityPressure,
        Integer executionAggressionPressure
) {
}
