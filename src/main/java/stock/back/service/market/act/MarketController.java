package stock.back.service.market.act;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.market.biz.MarketStatusService;
import stock.back.service.market.client.StockBatchAdminClient;
import stock.back.service.market.vo.MarketStatusUpdateRequest;
import stock.back.service.market.vo.SymbolMarketConfigResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
public class MarketController {

    private final MarketStatusService marketStatusService;
    private final StockBatchAdminClient stockBatchAdminClient;

    @PatchMapping("/{marketType}/symbols/{symbol}/status")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<SymbolMarketConfigResponse> updateMarketStatus(
            @PathVariable MarketType marketType,
            @PathVariable String symbol,
            @RequestBody MarketStatusUpdateRequest request
    ) {
        SymbolMarketConfigResponse response = marketStatusService.updateMarketStatus(marketType, symbol, request);
        if (marketType == MarketType.ORDER_BOOK
                && request != null
                && request.marketStatus() == MarketSessionStatus.CLOSED) {
            stockBatchAdminClient.runMarketCloseRollover(response.symbol());
        }
        return ResponseDataDTO.of(response);
    }
}
