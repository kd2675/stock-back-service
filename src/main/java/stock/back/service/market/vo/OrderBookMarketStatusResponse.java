package stock.back.service.market.vo;

import java.util.List;

public record OrderBookMarketStatusResponse(
        boolean enabled,
        long openOrderCount,
        long todayExecutionCount,
        List<SymbolMarketConfigResponse> configs
) {
}
