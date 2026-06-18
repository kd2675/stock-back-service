package stock.back.service.market.vo;

public record InstrumentResponse(
        String symbol,
        String name,
        String market
) {
}
