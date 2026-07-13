package stock.back.service.market.act;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.market.biz.AutoMarketStatusQueryService;
import stock.back.service.market.biz.MarketCatalogQueryService;
import stock.back.service.market.biz.OrderBookCandleQueryService;
import stock.back.service.market.biz.OrderBookInstrumentCommandService;
import stock.back.service.market.biz.InstrumentMarketReportQueryService;
import stock.back.service.market.biz.OrderBookMarketStatusQueryService;
import stock.back.service.market.biz.OrderBookQueryService;
import stock.back.service.market.vo.AutoMarketStatusResponse;
import stock.back.service.market.vo.OrderBookCandleResponse;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;
import stock.back.service.market.vo.OrderBookInstrumentTradingRulesRequest;
import stock.back.service.market.vo.OrderBookMarketStatusResponse;
import stock.back.service.market.vo.InstrumentMarketReportResponse;
import stock.back.service.market.vo.OrderBookRecentExecutionResponse;
import stock.back.service.market.vo.OrderBookResponse;
import stock.back.service.market.vo.OrderBookTradeSummaryResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
public class OrderBookMarketController {

    private final MarketCatalogQueryService marketCatalogQueryService;
    private final OrderBookInstrumentCommandService orderBookInstrumentCommandService;
    private final OrderBookQueryService orderBookQueryService;
    private final OrderBookCandleQueryService orderBookCandleQueryService;
    private final OrderBookMarketStatusQueryService orderBookMarketStatusQueryService;
    private final AutoMarketStatusQueryService autoMarketStatusQueryService;
    private final InstrumentMarketReportQueryService instrumentMarketReportQueryService;

    @GetMapping("/order-book-instruments")
    public ResponseDataDTO<List<OrderBookInstrumentResponse>> getOrderBookInstruments() {
        return ResponseDataDTO.of(marketCatalogQueryService.getOrderBookInstruments());
    }

    @GetMapping("/order-book-instruments/{symbol}/market-report")
    public ResponseDataDTO<InstrumentMarketReportResponse> getInstrumentMarketReport(@PathVariable String symbol) {
        return ResponseDataDTO.of(instrumentMarketReportQueryService.getInstrumentMarketReport(symbol));
    }

    @PostMapping("/order-book-instruments")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<OrderBookInstrumentResponse> createOrderBookInstrument(
            @Valid @RequestBody OrderBookInstrumentRequest request
    ) {
        return ResponseDataDTO.of(orderBookInstrumentCommandService.createOrderBookInstrument(request));
    }

    @PatchMapping("/order-book-instruments/{symbol}/trading-rules")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<OrderBookInstrumentResponse> updateOrderBookInstrumentTradingRules(
            @PathVariable String symbol,
            @Valid @RequestBody OrderBookInstrumentTradingRulesRequest request
    ) {
        return ResponseDataDTO.of(orderBookInstrumentCommandService.updateTradingRules(symbol, request));
    }

    @GetMapping("/order-books/{symbol}")
    public ResponseDataDTO<OrderBookResponse> getOrderBook(@PathVariable String symbol) {
        return ResponseDataDTO.of(orderBookQueryService.getOrderBook(symbol));
    }

    @GetMapping("/order-books/{symbol}/trade-summary")
    public ResponseDataDTO<OrderBookTradeSummaryResponse> getOrderBookTradeSummary(@PathVariable String symbol) {
        return ResponseDataDTO.of(orderBookQueryService.getOrderBookTradeSummary(symbol));
    }

    @GetMapping("/order-books/{symbol}/executions/recent")
    public ResponseDataDTO<List<OrderBookRecentExecutionResponse>> getRecentOrderBookExecutions(@PathVariable String symbol) {
        return ResponseDataDTO.of(orderBookQueryService.getRecentOrderBookExecutions(symbol));
    }

    @GetMapping("/order-books/{symbol}/candles/{interval}")
    public ResponseDataDTO<List<OrderBookCandleResponse>> getOrderBookCandles(
            @PathVariable String symbol,
            @PathVariable String interval
    ) {
        return ResponseDataDTO.of(orderBookCandleQueryService.getOrderBookCandles(symbol, interval));
    }

    @GetMapping("/order-book-market")
    public ResponseDataDTO<OrderBookMarketStatusResponse> getOrderBookMarketStatus(
            @RequestParam(defaultValue = "true") boolean includeConfigs,
            @RequestParam(defaultValue = "true") boolean includeTodayExecution
    ) {
        return ResponseDataDTO.of(orderBookMarketStatusQueryService.getOrderBookMarketStatus(includeConfigs, includeTodayExecution));
    }

    @GetMapping("/auto-market")
    public ResponseDataDTO<AutoMarketStatusResponse> getAutoMarketStatus(
            @RequestParam(defaultValue = "true") boolean includeConfigs,
            @RequestParam(defaultValue = "true") boolean includeParticipants,
            @RequestParam(defaultValue = "true") boolean includeParticipantSymbolConfigs,
            @RequestParam(defaultValue = "true") boolean includeParticipantProfileConfigs,
            @RequestParam(defaultValue = "true") boolean includeListingAutoAccounts,
            @RequestParam(defaultValue = "true") boolean includeRuntimeMetrics,
            @RequestParam(defaultValue = "true") boolean includeSalaryEligibility,
            @RequestParam(required = false) String participantSymbolConfigUserKey
    ) {
        return ResponseDataDTO.of(autoMarketStatusQueryService.getAutoMarketStatus(
                includeConfigs,
                includeParticipants,
                includeParticipantSymbolConfigs,
                includeParticipantProfileConfigs,
                includeListingAutoAccounts,
                includeRuntimeMetrics,
                includeSalaryEligibility,
                participantSymbolConfigUserKey
        ));
    }
}
