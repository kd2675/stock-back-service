package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorporateActionCommandServiceTest {

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockCorporateActionRepository stockCorporateActionRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private CorporateActionCommandService service;

    @BeforeEach
    void setUp() {
        service = new CorporateActionCommandService(
                stockOrderBookInstrumentRepository,
                stockCorporateActionRepository,
                stockPriceRepository,
                jdbcTemplate
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
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq("ZQ001"))).thenReturn(0L);
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
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq("ZQ001"))).thenReturn(3L);

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

        var response = service.applyCorporateAction(
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
        verify(jdbcTemplate, never()).queryForObject(any(String.class), eq(Long.class), eq("ZQ001"));
    }
}
