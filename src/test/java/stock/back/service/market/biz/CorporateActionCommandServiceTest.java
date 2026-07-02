package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.CorporateActionRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorporateActionCommandServiceTest {

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockCorporateActionRepository stockCorporateActionRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    private JdbcTemplate jdbcTemplate;

    private SimulationClockService simulationClockService;
    private CorporateActionCommandService service;
    private final LocalDateTime simulationNow = LocalDateTime.of(2026, 7, 1, 10, 0);

    @BeforeEach
    void setUp() {
        jdbcTemplate = createJdbcTemplate();
        simulationClockService = mock(SimulationClockService.class);
        lenient().when(simulationClockService.currentMarketDateTime()).thenReturn(simulationNow);
        service = new CorporateActionCommandService(
                stockOrderBookInstrumentRepository,
                stockCorporateActionRepository,
                stockPriceRepository,
                jdbcTemplate,
                simulationClockService
        );
    }

    @Test
    void applyCorporateAction_paidInCapitalIncrease_recordsScheduledEventWithoutImmediateShareIncrease() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "테스트",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("30.00")
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("ZQ001"))
                .thenReturn(Optional.of(StockPrice.initial("ZQ001", new BigDecimal("70000.00"))));

        var response = service.applyCorporateAction(
                " zq001 ",
                new CorporateActionRequest(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        10000L,
                        new BigDecimal("50000.00"),
                        null,
                        null,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 3),
                        LocalDate.of(2026, 7, 8),
                        null,
                        null,
                        " 유상증자 "
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.PAID_IN_CAPITAL_INCREASE);
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("68181.82"));
        assertThat(actionCaptor.getValue().getDescription()).isEqualTo("유상증자");
        assertThat(actionCaptor.getValue().getCreatedAt()).isEqualTo(simulationNow);
    }

    @Test
    void applyCorporateAction_openOrderBookOrders_throwsConflictBeforeSave() {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "테스트",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("30.00")
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));
        jdbcTemplate.update(
                "insert into stock_order(symbol, market_type, status) values (?, 'ORDER_BOOK', 'PENDING')",
                "ZQ001"
        );
        jdbcTemplate.update(
                "insert into stock_order(symbol, market_type, status) values (?, 'ORDER_BOOK', 'PARTIALLY_FILLED')",
                "ZQ001"
        );
        jdbcTemplate.update(
                "insert into stock_order(symbol, market_type, status) values (?, 'SPECIFIC_PRICE', 'PENDING')",
                "ZQ001"
        );

        assertThatThrownBy(() -> service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.CASH_DIVIDEND,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 3),
                        null,
                        null,
                        new BigDecimal("1000.00"),
                        null
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Corporate action requires no open order book orders: ZQ001");

        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_delisting_skipsOpenOrderPrecondition() {
        JdbcTemplate unusedJdbcTemplate = mock(JdbcTemplate.class);
        CorporateActionCommandService delistingService = new CorporateActionCommandService(
                stockOrderBookInstrumentRepository,
                stockCorporateActionRepository,
                stockPriceRepository,
                unusedJdbcTemplate,
                simulationClockService
        );
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "테스트",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("30.00")
        );
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));

        var response = delistingService.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.DELISTING,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 7, 10),
                        null,
                        "상장폐지"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.DELISTING);
        verifyNoInteractions(unusedJdbcTemplate);
    }

    private JdbcTemplate createJdbcTemplate() {
        JdbcTemplate template = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:corporate_action_command_%d;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(System.nanoTime()),
                "sa",
                ""
        ));
        template.execute("""
                create table stock_order (
                    id bigint generated by default as identity primary key,
                    symbol varchar(20) not null,
                    market_type varchar(32) not null,
                    status varchar(32) not null
                )
                """);
        return template;
    }
}
