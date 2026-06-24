package stock.back.service.market.vo;

import java.util.List;

public record VirtualMarketStatusResponse(
        boolean enabled,
        long openOrderCount,
        long todayExecutionCount,
        List<SymbolMarketConfigResponse> configs
) {
}
