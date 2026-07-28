package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import web.common.core.simulation.SimulationClockSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderBookInstrumentCommandServiceTest {

    @Mock
    private StockInstrumentRepository stockInstrumentRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private StockAutoMarketConfigRepository stockAutoMarketConfigRepository;

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;

    @Mock
    private StockCorporateActionRepository stockCorporateActionRepository;

    @Mock
    private SimulationClockService simulationClockService;

    @Mock
    private MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    private final LocalDateTime simulationNow = LocalDateTime.of(2026, 7, 1, 5, 0);
    private JdbcTemplate jdbcTemplate;

    private OrderBookInstrumentCommandService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = createJdbcTemplate();
        lenient().when(simulationClockService.currentMarketDateTime()).thenReturn(simulationNow);
        lenient().when(simulationClockService.currentSnapshot())
                .thenReturn(pausedClock(simulationNow));
        lenient().when(marketLedgerFreezeGuard.acquireMutationPermit(any()))
                .thenReturn(simulationNow.toLocalDate());
        service = new OrderBookInstrumentCommandService(
                stockInstrumentRepository,
                stockPriceRepository,
                stockAutoMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderBookMarketConfigRepository,
                stockCorporateActionRepository,
                jdbcTemplate,
                simulationClockService,
                marketLedgerFreezeGuard
        );
    }

    @Test
    void createOrderBookInstrument_runningClock_allowsStagingUntilDomainValidation() {
        when(simulationClockService.currentSnapshot())
                .thenReturn(runningClock(simulationNow));
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);

        assertThatThrownBy(() -> service.createOrderBookInstrument(
                new OrderBookInstrumentRequest(
                        "zq001",
                        "제로큐 주문장",
                        "ORDERBOOK",
                        new BigDecimal("70000.00"),
                        100000L,
                        null,
                        null
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Order book symbol already exists: ZQ001");

        verify(marketLedgerFreezeGuard).acquireMutationPermit(
                "order-book instrument staging"
        );
        verify(stockOrderBookInstrumentRepository, never()).save(any());
        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void createOrderBookInstrument_existingOrderBookSymbol_throwsConflictBeforeSave() {
        when(stockInstrumentRepository.existsById("ZQ001")).thenReturn(false);
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);

        assertThatThrownBy(() -> service.createOrderBookInstrument(
                new OrderBookInstrumentRequest(
                        "zq001",
                        "제로큐 주문장",
                        "ORDERBOOK",
                        new BigDecimal("70000.00"),
                        100000L,
                        null,
                        null
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Order book symbol already exists: ZQ001");

        verify(stockOrderBookInstrumentRepository, never()).save(any());
        verify(stockCorporateActionRepository, never()).save(any());
    }

    private JdbcTemplate createJdbcTemplate() {
        JdbcTemplate template = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:order_book_instrument_command_service_test_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        template.execute("""
                create table stock_account (
                    id bigint generated by default as identity primary key,
                    user_key varchar(64) not null,
                    status varchar(20) not null,
                    participant_category varchar(30) not null default 'MANUAL_PARTICIPANT',
                    cash_balance decimal(19, 2) not null,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        template.execute("""
                create table stock_holding (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null,
                    average_price decimal(19, 2) not null,
                    updated_at timestamp not null
                )
                """);
        return template;
    }

    private SimulationClockSnapshot pausedClock(LocalDateTime simulationDateTime) {
        return clock(simulationDateTime, false);
    }

    private SimulationClockSnapshot runningClock(LocalDateTime simulationDateTime) {
        return clock(simulationDateTime, true);
    }

    private SimulationClockSnapshot clock(
            LocalDateTime simulationDateTime,
            boolean running
    ) {
        LocalDate date = simulationDateTime.toLocalDate();
        return new SimulationClockSnapshot(
                date,
                simulationDateTime,
                date.atStartOfDay(),
                simulationDateTime,
                date.atStartOfDay(),
                7_200,
                running,
                false,
                0L,
                running ? simulationDateTime : null,
                running ? simulationDateTime : null
        );
    }
}
