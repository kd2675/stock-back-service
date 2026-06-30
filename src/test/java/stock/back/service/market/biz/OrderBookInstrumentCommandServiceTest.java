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
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.OrderBookInstrumentRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private OrderBookInstrumentCommandService service;

    @BeforeEach
    void setUp() {
        service = new OrderBookInstrumentCommandService(
                stockInstrumentRepository,
                stockPriceRepository,
                stockAutoMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderBookMarketConfigRepository,
                stockCorporateActionRepository,
                stockListingAutoAccountConfigRepository,
                jdbcTemplate
        );
    }

    @Test
    void createOrderBookInstrument_validRequest_recordsInitialIssueAndListingAccount() {
        when(stockInstrumentRepository.existsById("ZQ001")).thenReturn(false);
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(false);
        when(stockOrderBookInstrumentRepository.save(any(StockOrderBookInstrument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.empty());
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.eq("select id from stock_account where user_key = ?"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq("stock-listing-zq001")
        )).thenReturn(123L);

        var response = service.createOrderBookInstrument(
                new OrderBookInstrumentRequest(
                        " zq001 ",
                        "제로큐 주문장",
                        "",
                        new BigDecimal("70000.00"),
                        100000L,
                        new BigDecimal("5.00"),
                        new BigDecimal("30.00"),
                        null
                )
        );

        ArgumentCaptor<StockCorporateAction> actionCaptor = ArgumentCaptor.forClass(StockCorporateAction.class);
        ArgumentCaptor<StockListingAutoAccountConfig> listingConfigCaptor = ArgumentCaptor.forClass(StockListingAutoAccountConfig.class);
        verify(stockCorporateActionRepository).save(actionCaptor.capture());
        verify(stockListingAutoAccountConfigRepository).save(listingConfigCaptor.capture());
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.issuedShares()).isEqualTo(100000L);
        assertThat(response.tickSize()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.INITIAL_ISSUE);
        assertThat(actionCaptor.getValue().getStatus()).isEqualTo(StockCorporateActionStatus.LISTED);
        assertThat(listingConfigCaptor.getValue().getUserKey()).isEqualTo("stock-listing-zq001");
        assertThat(listingConfigCaptor.getValue().getDisplayName()).isEqualTo("제로큐 주문장 상장주관사");
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("insert into stock_account"),
                org.mockito.ArgumentMatchers.eq("stock-listing-zq001"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("insert into stock_holding"),
                org.mockito.ArgumentMatchers.eq(123L),
                org.mockito.ArgumentMatchers.eq("ZQ001"),
                org.mockito.ArgumentMatchers.eq(100000L),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("70000.00")),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
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
                        null,
                        null
                )
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Order book symbol already exists: ZQ001");

        verify(stockOrderBookInstrumentRepository, never()).save(any());
        verify(stockCorporateActionRepository, never()).save(any());
    }
}
