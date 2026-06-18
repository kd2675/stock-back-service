package stock.back.service.trading.act;

import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.trading.biz.TradingService;
import stock.back.service.trading.vo.ExecutionResponse;
import stock.back.service.trading.vo.HoldingResponse;
import stock.back.service.trading.vo.OrderRequest;
import stock.back.service.trading.vo.OrderResponse;
import stock.back.service.trading.vo.PortfolioResponse;
import stock.back.service.trading.vo.PortfolioSnapshotResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/api/stock/v1")
@RequirePrincipalRole
@RequiredArgsConstructor
public class TradingController {

    private final TradingService tradingService;

    @GetMapping("/portfolio/me")
    public ResponseDataDTO<PortfolioResponse> getMyPortfolio(UserContext userContext) {
        return ResponseDataDTO.of(tradingService.getPortfolio(userContext.getUserKey()));
    }

    @GetMapping("/portfolio/me/snapshots")
    public ResponseDataDTO<List<PortfolioSnapshotResponse>> getMyPortfolioSnapshots(UserContext userContext) {
        return ResponseDataDTO.of(tradingService.getPortfolioSnapshots(userContext.getUserKey()));
    }

    @GetMapping("/orders")
    public ResponseDataDTO<List<OrderResponse>> getOrders(UserContext userContext) {
        return ResponseDataDTO.of(tradingService.getOrders(userContext.getUserKey()));
    }

    @PostMapping("/orders")
    public ResponseDataDTO<OrderResponse> placeOrder(
            UserContext userContext,
            @Valid @RequestBody OrderRequest request
    ) {
        return ResponseDataDTO.of(tradingService.placeOrder(userContext.getUserKey(), request), "Order accepted");
    }

    @DeleteMapping("/orders/{orderId}")
    public ResponseDataDTO<OrderResponse> cancelOrder(UserContext userContext, @PathVariable Long orderId) {
        return ResponseDataDTO.of(tradingService.cancelOrder(userContext.getUserKey(), orderId), "Order cancelled");
    }

    @GetMapping("/executions")
    public ResponseDataDTO<List<ExecutionResponse>> getExecutions(UserContext userContext) {
        return ResponseDataDTO.of(tradingService.getExecutions(userContext.getUserKey()));
    }

    @GetMapping("/holdings")
    public ResponseDataDTO<List<HoldingResponse>> getHoldings(UserContext userContext) {
        return ResponseDataDTO.of(tradingService.getHoldings(userContext.getUserKey()));
    }
}
