package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.entity.StockVirtualMarketConfig;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockVirtualMarketConfigRepository;
import stock.back.service.market.vo.MarketStatusUpdateRequest;
import stock.back.service.market.vo.SymbolMarketConfigResponse;
import stock.back.service.market.vo.VirtualMarketStatusResponse;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MarketStatusService {

    private final StockVirtualMarketConfigRepository stockVirtualMarketConfigRepository;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
    private final StockOrderRepository stockOrderRepository;
    private final StockExecutionMarketViewRepository stockExecutionMarketViewRepository;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;

    @Transactional
    public SymbolMarketConfigResponse updateMarketStatus(MarketType marketType, String symbol, MarketStatusUpdateRequest request) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (marketType == null) {
            throw StockException.badRequest("Market type is required");
        }
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (request == null || (request.enabled() == null && request.marketStatus() == null)) {
            throw StockException.badRequest("Market status update requires enabled or marketStatus");
        }
        if (marketType == MarketType.VIRTUAL_PRICE) {
            StockVirtualMarketConfig config = stockVirtualMarketConfigRepository.findById(normalizedSymbol)
                    .orElseThrow(() -> StockException.notFound("Unknown virtual market symbol: " + normalizedSymbol));
            config.updateStatus(request.enabled(), request.marketStatus());
            return toVirtualMarketConfigResponse(config);
        }
        StockOrderBookMarketConfig config = stockOrderBookMarketConfigRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Unknown order book market symbol: " + normalizedSymbol));
        config.updateStatus(request.enabled(), request.marketStatus());
        return toOrderBookMarketConfigResponse(config);
    }

    @Transactional(readOnly = true)
    public VirtualMarketStatusResponse getVirtualMarketStatus() {
        List<SymbolMarketConfigResponse> configs = stockVirtualMarketConfigRepository.findAll().stream()
                .sorted((left, right) -> left.getSymbol().compareTo(right.getSymbol()))
                .map(this::toVirtualMarketConfigResponse)
                .toList();
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        long openOrderCount = stockOrderRepository.countByMarketTypeAndStatusIn(MarketType.VIRTUAL_PRICE, openStatuses);
        long todayExecutionCount = stockExecutionMarketViewRepository.countExecutionsFromBySource(
                simulationClockService.currentMarketDayStart(),
                ExecutionSource.VIRTUAL_MARKET_PRICE
        );
        return new VirtualMarketStatusResponse(
                simulationMarketSessionService.isRegularSession() && configs.stream().anyMatch(this::isConfigOpen),
                openOrderCount,
                todayExecutionCount,
                configs
        );
    }

    private SymbolMarketConfigResponse toVirtualMarketConfigResponse(StockVirtualMarketConfig config) {
        return new SymbolMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                normalizeMarketSessionStatus(config.getMarketStatus())
        );
    }

    private SymbolMarketConfigResponse toOrderBookMarketConfigResponse(StockOrderBookMarketConfig config) {
        return new SymbolMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                normalizeMarketSessionStatus(config.getMarketStatus())
        );
    }

    private boolean isConfigOpen(SymbolMarketConfigResponse config) {
        return config.enabled() && config.marketStatus() == MarketSessionStatus.OPEN;
    }

    private MarketSessionStatus normalizeMarketSessionStatus(MarketSessionStatus marketStatus) {
        return marketStatus == null ? MarketSessionStatus.OPEN : marketStatus;
    }

}
