package stock.back.service.market.act;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.market.biz.BatchJobSignalService;
import stock.back.service.market.biz.MarketStatusService;
import stock.back.service.market.vo.MarketStatusUpdateRequest;
import stock.back.service.market.vo.SymbolMarketConfigResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
public class MarketController {

    private final MarketStatusService marketStatusService;
    private final BatchJobSignalService batchJobSignalService;

    @PatchMapping("/{marketType}/symbols/{symbol}/status")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<SymbolMarketConfigResponse> updateMarketStatus(
            @PathVariable MarketType marketType,
            @PathVariable String symbol,
            @RequestBody MarketStatusUpdateRequest request,
            UserContext userContext
    ) {
        SymbolMarketConfigResponse response = marketStatusService.updateMarketStatus(marketType, symbol, request);
        if (marketType == MarketType.ORDER_BOOK
                && request != null
                && request.marketStatus() == MarketSessionStatus.CLOSED) {
            batchJobSignalService.enqueueMarketCloseRollover(response.symbol(), userContext.getUserKey());
        }
        if (marketType == MarketType.ORDER_BOOK
                && request != null
                && request.marketStatus() == MarketSessionStatus.HALTED) {
            batchJobSignalService.enqueueOpenOrderBookOrderCancel(response.symbol(), userContext.getUserKey());
        }
        if (marketType == MarketType.ORDER_BOOK
                && request != null
                && request.marketStatus() == MarketSessionStatus.CIRCUIT_BREAKER) {
            batchJobSignalService.enqueueOpenOrderBookOrderCancel(response.symbol(), userContext.getUserKey());
        }
        return ResponseDataDTO.of(response);
    }
}
