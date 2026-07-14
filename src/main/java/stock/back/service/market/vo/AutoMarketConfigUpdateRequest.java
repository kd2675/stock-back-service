package stock.back.service.market.vo;

public record AutoMarketConfigUpdateRequest(
        Boolean enabled,
        Integer maxOrderQuantity,
        Integer orderTtlSeconds,
        AutoMarketDistributionBiasRequest primaryDistributionBias,
        AutoMarketDistributionBiasRequest secondaryDistributionBias
) {
}
