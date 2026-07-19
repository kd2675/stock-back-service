package stock.back.service.market.vo;

public record AutoMarketConfigUpdateRequest(
        Boolean enabled,
        Integer maxOrderQuantity,
        Integer orderTtlSeconds,
        AutoMarketRegimeCountWeightsRequest primaryRegimeCountWeights,
        AutoMarketDistributionBiasRequest primaryDistributionBias,
        AutoMarketDistributionBiasRequest secondaryDistributionBias
) {
}
