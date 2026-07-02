package stock.back.service.market.act;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import stock.back.service.market.biz.MarketCatalogQueryService;
import stock.back.service.market.biz.MarketStatusService;
import stock.back.service.market.biz.SimulationClockService;
import stock.back.service.market.stream.PriceStreamService;
import stock.back.service.market.vo.InstrumentResponse;
import stock.back.service.market.vo.PriceResponse;
import stock.back.service.market.vo.PriceTickResponse;
import stock.back.service.market.vo.RankingResponse;
import stock.back.service.market.vo.SimulationClockResponse;
import stock.back.service.market.vo.VirtualMarketStatusResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/api/stock/v1/markets")
@RequiredArgsConstructor
public class MarketCatalogController {

    private final MarketCatalogQueryService marketCatalogQueryService;
    private final MarketStatusService marketStatusService;
    private final PriceStreamService priceStreamService;
    private final SimulationClockService simulationClockService;

    @GetMapping("/instruments")
    public ResponseDataDTO<List<InstrumentResponse>> getInstruments() {
        return ResponseDataDTO.of(marketCatalogQueryService.getInstruments());
    }

    @GetMapping("/prices")
    public ResponseDataDTO<List<PriceResponse>> getPrices() {
        return ResponseDataDTO.of(marketCatalogQueryService.getPrices());
    }

    @GetMapping(value = "/prices/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPrices() {
        return priceStreamService.connect();
    }

    @GetMapping("/prices/{symbol}/ticks")
    public ResponseDataDTO<List<PriceTickResponse>> getPriceTicks(@PathVariable String symbol) {
        return ResponseDataDTO.of(marketCatalogQueryService.getPriceTicks(symbol));
    }

    @GetMapping("/rankings")
    public ResponseDataDTO<List<RankingResponse>> getRankings() {
        return ResponseDataDTO.of(marketCatalogQueryService.getRankings());
    }

    @GetMapping("/simulation-clock")
    public ResponseDataDTO<SimulationClockResponse> getSimulationClock() {
        return ResponseDataDTO.of(simulationClockService.currentResponse());
    }

    @GetMapping("/virtual-market")
    public ResponseDataDTO<VirtualMarketStatusResponse> getVirtualMarketStatus() {
        return ResponseDataDTO.of(marketStatusService.getVirtualMarketStatus());
    }
}
