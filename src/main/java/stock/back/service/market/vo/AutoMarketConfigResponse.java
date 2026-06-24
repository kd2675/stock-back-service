package stock.back.service.market.vo;

public record AutoMarketConfigResponse(
        String symbol,
        boolean enabled,
        int intensity,
        int maxOrderQuantity,
        int orderTtlSeconds
) {
}
