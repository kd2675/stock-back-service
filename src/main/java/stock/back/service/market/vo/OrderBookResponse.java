package stock.back.service.market.vo;

import java.util.List;

public record OrderBookResponse(
        String symbol,
        List<OrderBookLevelResponse> bids,
        List<OrderBookLevelResponse> asks
) {
}
