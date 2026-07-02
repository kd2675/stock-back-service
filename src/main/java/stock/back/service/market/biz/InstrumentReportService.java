package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockInstrumentReportEvent;
import stock.back.service.database.entity.StockInstrumentReportEventType;
import stock.back.service.database.repository.StockInstrumentReportEventRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.InstrumentReportRequest;
import stock.back.service.market.vo.InstrumentReportResponse;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InstrumentReportService {

    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockInstrumentReportEventRepository stockInstrumentReportEventRepository;
    private final SimulationClockService simulationClockService;

    @Transactional(readOnly = true)
    public List<InstrumentReportResponse> getInstrumentReports(String symbol) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        return stockInstrumentReportEventRepository.findTop50BySymbolOrderByCreatedAtDescIdDesc(normalizedSymbol).stream()
                .map(this::toInstrumentReportResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public InstrumentReportResponse getLatestInstrumentReport(String symbol) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        return stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc(normalizedSymbol)
                .filter(event -> event.getEventType() != StockInstrumentReportEventType.DELETE)
                .map(this::toInstrumentReportResponse)
                .orElse(null);
    }

    @Transactional
    public InstrumentReportResponse publishInstrumentReport(String symbol, InstrumentReportRequest request, String createdBy) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        validateInstrumentReportRequest(request);
        StockInstrumentReportEvent event = StockInstrumentReportEvent.publish(
                normalizedSymbol,
                MarketTextNormalizer.text(request.title()),
                MarketTextNormalizer.text(request.summary()),
                request.score(),
                MarketTextNormalizer.optionalText(request.riseReason()),
                MarketTextNormalizer.optionalText(request.fallReason()),
                MarketTextNormalizer.text(createdBy),
                simulationClockService.currentMarketDateTime()
        );
        return toInstrumentReportResponse(stockInstrumentReportEventRepository.save(event));
    }

    @Transactional
    public InstrumentReportResponse updateInstrumentReport(String symbol, InstrumentReportRequest request, String createdBy) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        validateInstrumentReportRequest(request);
        StockInstrumentReportEvent latest = stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc(normalizedSymbol)
                .filter(event -> event.getEventType() != StockInstrumentReportEventType.DELETE)
                .orElseThrow(() -> StockException.notFound("Instrument report not found: " + normalizedSymbol));
        StockInstrumentReportEvent event = StockInstrumentReportEvent.update(
                latest.getSymbol(),
                MarketTextNormalizer.text(request.title()),
                MarketTextNormalizer.text(request.summary()),
                request.score(),
                MarketTextNormalizer.optionalText(request.riseReason()),
                MarketTextNormalizer.optionalText(request.fallReason()),
                MarketTextNormalizer.text(createdBy),
                simulationClockService.currentMarketDateTime()
        );
        return toInstrumentReportResponse(stockInstrumentReportEventRepository.save(event));
    }

    @Transactional
    public InstrumentReportResponse deleteInstrumentReport(String symbol, String createdBy) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc(normalizedSymbol)
                .filter(event -> event.getEventType() != StockInstrumentReportEventType.DELETE)
                .orElseThrow(() -> StockException.notFound("Instrument report not found: " + normalizedSymbol));
        StockInstrumentReportEvent event = StockInstrumentReportEvent.delete(
                normalizedSymbol,
                "Deleted by admin",
                MarketTextNormalizer.text(createdBy),
                simulationClockService.currentMarketDateTime()
        );
        return toInstrumentReportResponse(stockInstrumentReportEventRepository.save(event));
    }

    private String requireOrderBookSymbol(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        return normalizedSymbol;
    }

    private void validateInstrumentReportRequest(InstrumentReportRequest request) {
        if (request == null) {
            throw StockException.badRequest("Instrument report is required");
        }
        if (MarketTextNormalizer.text(request.title()).isBlank()) {
            throw StockException.badRequest("Report title is required");
        }
        if (MarketTextNormalizer.text(request.summary()).isBlank()) {
            throw StockException.badRequest("Report summary is required");
        }
        if (request.score() == null || request.score() < 1 || request.score() > 10) {
            throw StockException.badRequest("Report score must be between 1 and 10");
        }
    }

    private InstrumentReportResponse toInstrumentReportResponse(StockInstrumentReportEvent event) {
        return new InstrumentReportResponse(
                event.getId(),
                event.getSymbol(),
                event.getEventType(),
                event.getTitle(),
                event.getSummary(),
                event.getScore(),
                event.getRiseReason(),
                event.getFallReason(),
                event.getDeleteReason(),
                event.getCreatedBy(),
                event.getCreatedAt()
        );
    }

}
