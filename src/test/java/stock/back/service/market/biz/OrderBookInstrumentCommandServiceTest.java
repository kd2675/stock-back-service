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
    private StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;

    @Mock
    private SimulationClockService simulationClockService;

    private JdbcTemplate jdbcTemplate;

    private OrderBookInstrumentCommandService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = createJdbcTemplate();
        lenient().when(simulationClockService.currentMarketDateTime()).thenReturn(LocalDateTime.of(2026, 7, 1, 10, 0));
        service = new OrderBookInstrumentCommandService(
                stockInstrumentRepository,
                stockPriceRepository,
                stockAutoMarketConfigRepository,
                stockOrderBookInstrumentRepository,
                stockOrderBookMarketConfigRepository,
                stockCorporateActionRepository,
                stockListingAutoAccountConfigRepository,
                jdbcTemplate,
                simulationClockService
        );
    }

    @Test
    void createOrderBookInstrument_validRequest_recordsInitialIssueAndListingAccount() {
        when(stockInstrumentRepository.existsById("ZQ001")).thenReturn(false);
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(false);
        when(stockOrderBookInstrumentRepository.save(any(StockOrderBookInstrument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockPriceRepository.findById("ZQ001")).thenReturn(Optional.empty());

        var response = service.createOrderBookInstrument(
                new OrderBookInstrumentRequest(
                        " zq001 ",
                        "제로큐 주문장",
                        "",
                        new BigDecimal("70000.00"),
                        100000L,
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
        assertThat(response.tickSize()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(actionCaptor.getValue().getActionType()).isEqualTo(StockCorporateActionType.INITIAL_ISSUE);
        assertThat(actionCaptor.getValue().getStatus()).isEqualTo(StockCorporateActionStatus.LISTED);
        assertThat(listingConfigCaptor.getValue().getUserKey()).isEqualTo("stock-listing-zq001");
        assertThat(listingConfigCaptor.getValue().getDisplayName()).isEqualTo("제로큐 주문장 상장주관사");
        Long accountId = jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                "stock-listing-zq001"
        );
        assertThat(accountId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select quantity from stock_holding where account_id = ? and symbol = ?",
                Long.class,
                accountId,
                "ZQ001"
        )).isEqualTo(100000L);
        assertThat(jdbcTemplate.queryForObject(
                "select average_price from stock_holding where account_id = ? and symbol = ?",
                BigDecimal.class,
                accountId,
                "ZQ001"
        )).isEqualByComparingTo(new BigDecimal("70000.00"));
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
}
