package stock.back.service.market.act;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import stock.back.service.market.biz.MarketService;
import stock.back.service.market.stream.PriceStreamService;
import stock.back.service.market.vo.InstrumentResponse;
import stock.back.service.market.vo.OrderBookResponse;
import stock.back.service.market.vo.PriceResponse;
import stock.back.service.market.vo.PriceTickResponse;
import stock.back.service.market.vo.RankingResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;
    private final PriceStreamService priceStreamService;

    @GetMapping("/instruments")
    public ResponseDataDTO<List<InstrumentResponse>> getInstruments() {
        return ResponseDataDTO.of(marketService.getInstruments());
    }

    @GetMapping("/prices")
    public ResponseDataDTO<List<PriceResponse>> getPrices() {
        return ResponseDataDTO.of(marketService.getPrices());
    }

    @GetMapping(value = "/prices/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPrices() {
        return priceStreamService.connect();
    }

    @GetMapping("/prices/{symbol}/ticks")
    public ResponseDataDTO<List<PriceTickResponse>> getPriceTicks(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketService.getPriceTicks(symbol));
    }

    @GetMapping("/order-books/{symbol}")
    public ResponseDataDTO<OrderBookResponse> getOrderBook(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketService.getOrderBook(symbol));
    }

    @GetMapping("/rankings")
    public ResponseDataDTO<List<RankingResponse>> getRankings() {
        return ResponseDataDTO.of(marketService.getRankings());
    }
}
