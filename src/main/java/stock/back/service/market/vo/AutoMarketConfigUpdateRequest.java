package stock.back.service.market.vo;

public record AutoMarketConfigUpdateRequest(
        Boolean enabled,
        Integer intensity,
        Integer maxOrderQuantity,
        Integer orderTtlSeconds
) {
}
