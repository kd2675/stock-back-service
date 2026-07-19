package stock.back.service.market.vo;

public record AutoMarketConfigResponse(
        String symbol,
        boolean enabled,
        int maxOrderQuantity,
        int orderTtlSeconds,
        AutoMarketRegimeCountWeightsResponse primaryRegimeCountWeights,
        AutoMarketDistributionBiasResponse primaryDistributionBias,
        AutoMarketDistributionBiasResponse secondaryDistributionBias,
        AutoMarketDailyRegimeResponse dailyRegime
) {
}
