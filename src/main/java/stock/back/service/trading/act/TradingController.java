package stock.back.service.trading.act;

import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.MarketType;
import stock.back.service.trading.biz.TradingService;
import stock.back.service.trading.vo.ExecutionResponse;
import stock.back.service.trading.vo.HoldingResponse;
import stock.back.service.trading.vo.OrderAmendRequest;
import stock.back.service.trading.vo.OrderCancelRequest;
import stock.back.service.trading.vo.OrderRequest;
import stock.back.service.trading.vo.OrderResponse;
import stock.back.service.trading.vo.PortfolioResponse;
import stock.back.service.trading.vo.PortfolioSnapshotResponse;
import stock.back.service.trading.vo.ProfitSummaryResponse;
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

    @GetMapping("/portfolio/me/profit-summary")
    public ResponseDataDTO<ProfitSummaryResponse> getMyProfitSummary(UserContext userContext) {
        return ResponseDataDTO.of(tradingService.getProfitSummary(userContext.getUserKey()));
    }

    @GetMapping("/orders")
    public ResponseDataDTO<List<OrderResponse>> getOrders(
            UserContext userContext,
            @RequestParam(required = false) MarketType marketType
    ) {
        return ResponseDataDTO.of(tradingService.getOrders(userContext.getUserKey(), marketType));
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

    @PatchMapping("/orders/{orderId}")
    public ResponseDataDTO<OrderResponse> amendOrder(
            UserContext userContext,
            @PathVariable Long orderId,
            @Valid @RequestBody OrderAmendRequest request
    ) {
        return ResponseDataDTO.of(tradingService.amendOrder(userContext.getUserKey(), orderId, request), "Order amended");
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ResponseDataDTO<OrderResponse> cancelOrderPartially(
            UserContext userContext,
            @PathVariable Long orderId,
            @Valid @RequestBody OrderCancelRequest request
    ) {
        return ResponseDataDTO.of(tradingService.cancelOrderPartially(userContext.getUserKey(), orderId, request), "Order cancelled");
    }

    @GetMapping("/executions")
    public ResponseDataDTO<List<ExecutionResponse>> getExecutions(
            UserContext userContext,
            @RequestParam(required = false) ExecutionSource source
    ) {
        return ResponseDataDTO.of(tradingService.getExecutions(userContext.getUserKey(), source));
    }

    @GetMapping("/holdings")
    public ResponseDataDTO<List<HoldingResponse>> getHoldings(UserContext userContext) {
        return ResponseDataDTO.of(tradingService.getHoldings(userContext.getUserKey()));
    }
}
