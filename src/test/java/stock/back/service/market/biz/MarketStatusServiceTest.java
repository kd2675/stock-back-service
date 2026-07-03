package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketStatusServiceTest {

    private static final LocalDateTime SIMULATION_NOW = LocalDateTime.of(2026, 7, 3, 10, 0);
    private static final LocalDateTime SIMULATION_DAY_START = SIMULATION_NOW.toLocalDate().atStartOfDay();

    @Mock
    private StockVirtualMarketConfigRepository stockVirtualMarketConfigRepository;

    @Mock
    private StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;

    @Mock
    private StockOrderRepository stockOrderRepository;

    @Mock
    private StockExecutionMarketViewRepository stockExecutionMarketViewRepository;

    @Mock
    private SimulationClockService simulationClockService;

    @Mock
    private SimulationMarketSessionService simulationMarketSessionService;

    private MarketStatusService service;

    @BeforeEach
    void setUp() {
        lenient().when(simulationClockService.currentMarketDayStart()).thenReturn(SIMULATION_DAY_START);
        lenient().when(simulationClockService.currentMarketDateTime()).thenReturn(SIMULATION_NOW);
        lenient().when(simulationMarketSessionService.isRegularSession()).thenReturn(true);
        service = new MarketStatusService(
                stockVirtualMarketConfigRepository,
                stockOrderBookMarketConfigRepository,
                stockOrderRepository,
                stockExecutionMarketViewRepository,
                simulationClockService,
                simulationMarketSessionService
        );
    }

    @Test
    void updateMarketStatus_virtualMarket_updatesConfig() {
        StockVirtualMarketConfig config = virtualMarketConfig("005930", true, MarketSessionStatus.OPEN);
        when(stockVirtualMarketConfigRepository.findById("005930")).thenReturn(Optional.of(config));

        var response = service.updateMarketStatus(
                MarketType.VIRTUAL_PRICE,
                "005930",
                new MarketStatusUpdateRequest(false, MarketSessionStatus.HALTED)
        );

        assertThat(response.symbol()).isEqualTo("005930");
        assertThat(response.enabled()).isFalse();
        assertThat(response.marketStatus()).isEqualTo(MarketSessionStatus.HALTED);
    }

    @Test
    void updateMarketStatus_orderBookMarket_updatesConfig() {
        StockOrderBookMarketConfig config = StockOrderBookMarketConfig.enabled("ZQ001");
        when(stockOrderBookMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        var response = service.updateMarketStatus(
                MarketType.ORDER_BOOK,
                "zq001",
                new MarketStatusUpdateRequest(true, MarketSessionStatus.CLOSED)
        );

        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.enabled()).isTrue();
        assertThat(response.marketStatus()).isEqualTo(MarketSessionStatus.CLOSED);
    }

    @Test
    void updateMarketStatus_orderBookOpenOutsideRegularSession_throwsConflict() {
        StockOrderBookMarketConfig config = orderBookConfig("ZQ001", MarketSessionStatus.HALTED, SIMULATION_DAY_START.plusHours(9));
        when(stockOrderBookMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        assertThatThrownBy(() -> service.updateMarketStatus(
                MarketType.ORDER_BOOK,
                "zq001",
                new MarketStatusUpdateRequest(true, MarketSessionStatus.OPEN)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("regular trading session");
    }

    @Test
    void updateMarketStatus_virtualMarketOpenOutsideRegularSession_throwsConflict() {
        StockVirtualMarketConfig config = virtualMarketConfig("005930", true, MarketSessionStatus.CLOSED);
        when(stockVirtualMarketConfigRepository.findById("005930")).thenReturn(Optional.of(config));
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        assertThatThrownBy(() -> service.updateMarketStatus(
                MarketType.VIRTUAL_PRICE,
                "005930",
                new MarketStatusUpdateRequest(true, MarketSessionStatus.OPEN)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("regular trading session");
    }

    @Test
    void updateMarketStatus_orderBookClosedToOpenOnSameSimulationDate_throwsConflict() {
        StockOrderBookMarketConfig config = orderBookConfig("ZQ001", MarketSessionStatus.CLOSED, SIMULATION_DAY_START.plusHours(9));
        when(stockOrderBookMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateMarketStatus(
                MarketType.ORDER_BOOK,
                "zq001",
                new MarketStatusUpdateRequest(true, MarketSessionStatus.OPEN)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("same simulation trading day");
    }

    @Test
    void updateMarketStatus_orderBookClosedToOpenOnLaterSimulationDate_updatesConfig() {
        StockOrderBookMarketConfig config = orderBookConfig("ZQ001", MarketSessionStatus.CLOSED, SIMULATION_DAY_START.minusDays(1).plusHours(18));
        when(stockOrderBookMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        var response = service.updateMarketStatus(
                MarketType.ORDER_BOOK,
                "zq001",
                new MarketStatusUpdateRequest(true, MarketSessionStatus.OPEN)
        );

        assertThat(response.marketStatus()).isEqualTo(MarketSessionStatus.OPEN);
    }

    @Test
    void updateMarketStatus_orderBookCircuitBreakerToOpen_throwsConflict() {
        StockOrderBookMarketConfig config = orderBookConfig("ZQ001", MarketSessionStatus.CIRCUIT_BREAKER, SIMULATION_DAY_START.minusDays(1).plusHours(10));
        when(stockOrderBookMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.updateMarketStatus(
                MarketType.ORDER_BOOK,
                "zq001",
                new MarketStatusUpdateRequest(true, MarketSessionStatus.OPEN)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Circuit breaker market resumes automatically");
    }

    @Test
    void updateMarketStatus_orderBookHaltedToOpenDuringRegularSession_updatesConfig() {
        StockOrderBookMarketConfig config = orderBookConfig("ZQ001", MarketSessionStatus.HALTED, SIMULATION_DAY_START.plusHours(9));
        when(stockOrderBookMarketConfigRepository.findById("ZQ001")).thenReturn(Optional.of(config));

        var response = service.updateMarketStatus(
                MarketType.ORDER_BOOK,
                "zq001",
                new MarketStatusUpdateRequest(true, MarketSessionStatus.OPEN)
        );

        assertThat(response.marketStatus()).isEqualTo(MarketSessionStatus.OPEN);
    }

    @Test
    void updateMarketStatus_emptyUpdate_throwsBadRequest() {
        assertThatThrownBy(() -> service.updateMarketStatus(
                MarketType.ORDER_BOOK,
                "zq001",
                new MarketStatusUpdateRequest(null, null)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Market status update requires enabled or marketStatus");
    }

    @Test
    void getVirtualMarketStatus_sortsConfigsAndAggregatesRuntimeCounts() {
        StockVirtualMarketConfig open = virtualMarketConfig("B001", true, MarketSessionStatus.OPEN);
        StockVirtualMarketConfig closed = virtualMarketConfig("A001", true, MarketSessionStatus.CLOSED);
        when(stockVirtualMarketConfigRepository.findAll()).thenReturn(List.of(open, closed));
        when(stockOrderRepository.countByMarketTypeAndStatusIn(
                MarketType.VIRTUAL_PRICE,
                List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED)
        )).thenReturn(3L);
        when(stockExecutionMarketViewRepository.countExecutionsBetweenBySource(
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(ExecutionSource.VIRTUAL_MARKET_PRICE)
        ))
                .thenReturn(7L);

        var response = service.getVirtualMarketStatus();

        assertThat(response.enabled()).isTrue();
        assertThat(response.openOrderCount()).isEqualTo(3L);
        assertThat(response.todayExecutionCount()).isEqualTo(7L);
        assertThat(response.configs()).extracting(config -> config.symbol()).containsExactly("A001", "B001");
    }

    @Test
    void getVirtualMarketStatus_outsideRegularSession_reportsDisabledEvenWhenConfigOpen() {
        StockVirtualMarketConfig open = virtualMarketConfig("B001", true, MarketSessionStatus.OPEN);
        when(stockVirtualMarketConfigRepository.findAll()).thenReturn(List.of(open));
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        var response = service.getVirtualMarketStatus();

        assertThat(response.enabled()).isFalse();
        assertThat(response.configs()).extracting(config -> config.marketStatus()).containsExactly(MarketSessionStatus.OPEN);
    }

    private StockOrderBookMarketConfig orderBookConfig(String symbol, MarketSessionStatus marketStatus, LocalDateTime updatedAt) {
        StockOrderBookMarketConfig config = StockOrderBookMarketConfig.enabled(symbol);
        config.updateStatus(true, marketStatus, updatedAt);
        return config;
    }

    private StockVirtualMarketConfig virtualMarketConfig(String symbol, boolean enabled, MarketSessionStatus marketStatus) {
        StockVirtualMarketConfig config = mock(StockVirtualMarketConfig.class);
        AtomicReference<Boolean> enabledHolder = new AtomicReference<>(enabled);
        AtomicReference<MarketSessionStatus> statusHolder = new AtomicReference<>(marketStatus);
        lenient().when(config.getSymbol()).thenReturn(symbol);
        lenient().when(config.getEnabled()).thenAnswer(invocation -> enabledHolder.get());
        lenient().when(config.getMarketStatus()).thenAnswer(invocation -> statusHolder.get());
        lenient().doAnswer(invocation -> {
            Boolean nextEnabled = invocation.getArgument(0);
            MarketSessionStatus nextStatus = invocation.getArgument(1);
            if (nextEnabled != null) {
                enabledHolder.set(nextEnabled);
            }
            if (nextStatus != null) {
                statusHolder.set(nextStatus);
            }
            return null;
        }).when(config).updateStatus(any(), any());
        lenient().doAnswer(invocation -> {
            Boolean nextEnabled = invocation.getArgument(0);
            MarketSessionStatus nextStatus = invocation.getArgument(1);
            if (nextEnabled != null) {
                enabledHolder.set(nextEnabled);
            }
            if (nextStatus != null) {
                statusHolder.set(nextStatus);
            }
            return null;
        }).when(config).updateStatus(any(), any(), any());
        return config;
    }
}
