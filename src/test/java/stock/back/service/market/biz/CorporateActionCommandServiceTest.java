package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionStatus;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        lenient().when(stockOrderBookInstrumentRepository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> stockOrderBookInstrumentRepository.findById(invocation.getArgument(0)));
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
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 5),
                        LocalDate.of(2026, 7, 8),
                        null,
                        null,
                        StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                        LocalDate.of(2026, 7, 3),
                        LocalDate.of(2026, 7, 4),
                        " 유상증자 "
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.PAID_IN_CAPITAL_INCREASE);
        assertThat(actionCaptor.getValue().getTheoreticalExRightsPrice()).isEqualByComparingTo(new BigDecimal("68181.00"));
        assertThat(actionCaptor.getValue().getDescription()).isEqualTo("유상증자");
        assertThat(actionCaptor.getValue().getCreatedAt()).isEqualTo(simulationNow);
    }

    @ParameterizedTest
    @EnumSource(value = StockCorporateActionType.class, names = "INITIAL_ISSUE", mode = EnumSource.Mode.EXCLUDE)
    void applyCorporateAction_disabledInstrument_throwsConflictWithoutSaving(StockCorporateActionType actionType) {
        StockOrderBookInstrument instrument = StockOrderBookInstrument.listed(
                "ZQ001",
                "테스트",
                "ORDERBOOK",
                new BigDecimal("70000.00"),
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("30.00")
        );
        instrument.delist();
        when(stockOrderBookInstrumentRepository.findById("ZQ001")).thenReturn(Optional.of(instrument));

        CorporateActionRequest request = new CorporateActionRequest(
                actionType,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> service.applyCorporateAction("ZQ001", request))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("disabled or delisted instrument: ZQ001");

        verify(stockOrderBookInstrumentRepository).findByIdForUpdate("ZQ001");
        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_paidInCapitalIncreaseWithoutSubscriptionEnd_defaultsToDayBeforePayment() {
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

        service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        10000L,
                        new BigDecimal("50000.00"),
                        null,
                        null,
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 5),
                        LocalDate.of(2026, 7, 8),
                        null,
                        null,
                        StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                        LocalDate.of(2026, 7, 3),
                        null,
                        "유상증자"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(actionCaptor.getValue().getSubscriptionEndDate()).isEqualTo(LocalDate.of(2026, 7, 4));
    }

    @Test
    void applyCorporateAction_shareholderAllocationWithoutSubscriptionStart_defaultsToExplicitRecordDate() {
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

        service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        10000L,
                        new BigDecimal("50000.00"),
                        null,
                        null,
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 4),
                        LocalDate.of(2026, 7, 7),
                        LocalDate.of(2026, 7, 10),
                        null,
                        null,
                        StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                        null,
                        LocalDate.of(2026, 7, 6),
                        "유상증자"
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        assertThat(actionCaptor.getValue().getSubscriptionStartDate()).isEqualTo(LocalDate.of(2026, 7, 4));
    }

    @Test
    void applyCorporateAction_activePaidInCapitalIncrease_throwsConflictBeforePriceRead() {
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
        when(stockCorporateActionRepository.existsBySymbolAndActionTypeInAndStatusIn(
                "ZQ001",
                List.of(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        StockCorporateActionType.STOCK_SPLIT,
                        StockCorporateActionType.BONUS_ISSUE,
                        StockCorporateActionType.STOCK_DIVIDEND,
                        StockCorporateActionType.DELISTING
                ),
                List.of(
                        StockCorporateActionStatus.ANNOUNCED,
                        StockCorporateActionStatus.EX_RIGHTS_APPLIED,
                        StockCorporateActionStatus.PAID
                )
        )).thenReturn(true);

        assertThatThrownBy(() -> service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        10000L,
                        new BigDecimal("50000.00"),
                        null,
                        null,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 4),
                        LocalDate.of(2026, 7, 8),
                        null,
                        null,
                        StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 3),
                        "유상증자"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("instrument-changing corporate action is already in progress");

        verifyNoInteractions(stockPriceRepository);
    }

    @Test
    void applyCorporateAction_stockSplitWhilePaidInIsActive_throwsConflict() {
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
        when(stockCorporateActionRepository.existsBySymbolAndActionTypeInAndStatusIn(
                "ZQ001",
                List.of(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        StockCorporateActionType.STOCK_SPLIT,
                        StockCorporateActionType.BONUS_ISSUE,
                        StockCorporateActionType.STOCK_DIVIDEND,
                        StockCorporateActionType.DELISTING
                ),
                List.of(
                        StockCorporateActionStatus.ANNOUNCED,
                        StockCorporateActionStatus.EX_RIGHTS_APPLIED,
                        StockCorporateActionStatus.PAID
                )
        )).thenReturn(true);

        assertThatThrownBy(() -> service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.STOCK_SPLIT,
                        null,
                        null,
                        1,
                        5,
                        null,
                        null,
                        LocalDate.of(2026, 7, 3),
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("instrument-changing corporate action is already in progress");

        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_delistingWhilePaidInIsActive_throwsConflict() {
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
        when(stockCorporateActionRepository.existsBySymbolAndActionTypeInAndStatusIn(
                "ZQ001",
                List.of(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        StockCorporateActionType.STOCK_SPLIT,
                        StockCorporateActionType.BONUS_ISSUE,
                        StockCorporateActionType.STOCK_DIVIDEND,
                        StockCorporateActionType.DELISTING
                ),
                List.of(
                        StockCorporateActionStatus.ANNOUNCED,
                        StockCorporateActionStatus.EX_RIGHTS_APPLIED,
                        StockCorporateActionStatus.PAID
                )
        )).thenReturn(true);

        assertThatThrownBy(() -> service.applyCorporateAction(
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
                        LocalDate.of(2026, 7, 3),
                        null,
                        "상장폐지"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("instrument-changing corporate action is already in progress");

        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_openOrderBookOrders_allowsFutureAnnouncement() {
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
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.of(StockPrice.initial(
                "ZQ001",
                new BigDecimal("70000.00"),
                simulationNow
        )));
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

        service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.CASH_DIVIDEND,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 3),
                        null,
                        null,
                        new BigDecimal("1000.00"),
                        null
                )
        );

        verify(stockCorporateActionRepository).save(any());
    }

    @Test
    void applyCorporateAction_cashDividendBeforeCurrentSimulationDate_throwsBadRequest() {
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

        assertThatThrownBy(() -> service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.CASH_DIVIDEND,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 6, 30),
                        LocalDate.of(2026, 7, 2),
                        null,
                        null,
                        new BigDecimal("1000.00"),
                        null
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("must be after current simulation date");

        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_paidInCapitalIncreaseSameDayPayment_throwsBadRequest() {
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

        assertThatThrownBy(() -> service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        10000L,
                        new BigDecimal("50000.00"),
                        null,
                        null,
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 3),
                        null,
                        null,
                        "유상증자"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("subscription end date must not be before current simulation date");

        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_paidInCapitalIncreasePaymentOnSubscriptionEndDate_throwsBadRequest() {
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

        assertThatThrownBy(() -> service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        10000L,
                        new BigDecimal("50000.00"),
                        null,
                        null,
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 4),
                        LocalDate.of(2026, 7, 5),
                        null,
                        null,
                        StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION,
                        LocalDate.of(2026, 7, 3),
                        LocalDate.of(2026, 7, 4),
                        "유상증자"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("dates must be ordered by subscription, payment, listing");

        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_cashDividendSameDayPayment_throwsBadRequest() {
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

        assertThatThrownBy(() -> service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.CASH_DIVIDEND,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 2),
                        null,
                        null,
                        new BigDecimal("1000.00"),
                        null
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("payment date must be after ex-dividend date");

        verify(stockCorporateActionRepository, never()).save(any());
        verify(stockCorporateActionRepository, never())
                .existsBySymbolAndActionTypeInAndStatusIn(anyString(), any(), any());
    }

    @Test
    void applyCorporateAction_stockSplitBeforeCurrentSimulationDate_throwsBadRequest() {
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

        assertThatThrownBy(() -> service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.STOCK_SPLIT,
                        null,
                        null,
                        1,
                        5,
                        null,
                        null,
                        LocalDate.of(2026, 6, 30),
                        null,
                        null,
                        "액면분할"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("must not be before current simulation date");

        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_stockDividendSameDayListing_throwsBadRequest() {
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

        assertThatThrownBy(() -> service.applyCorporateAction(
                "ZQ001",
                new CorporateActionRequest(
                        StockCorporateActionType.STOCK_DIVIDEND,
                        10000L,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 7, 2),
                        null,
                        LocalDate.of(2026, 7, 2),
                        null,
                        null,
                        "주식배당"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("listing date must be after ex-rights date");

        verify(stockCorporateActionRepository, never()).save(any());
    }

    @Test
    void applyCorporateAction_delistingBeforeCurrentSimulationDate_throwsBadRequest() {
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

        assertThatThrownBy(() -> service.applyCorporateAction(
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
                        LocalDate.of(2026, 6, 30),
                        null,
                        "상장폐지"
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("must not be before current simulation date");

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
