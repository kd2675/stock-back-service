package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlementStatus;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashDividendGuidanceQueryServiceTest {

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private StockCorporateActionRepository stockCorporateActionRepository;

    @Mock
    private StockCorporateActionEntitlementRepository stockCorporateActionEntitlementRepository;

    @Mock
    private CashDividendGuidanceSnapshotReader cashDividendGuidanceSnapshotReader;

    private CashDividendGuidanceQueryService service;

    @BeforeEach
    void setUp() {
        service = new CashDividendGuidanceQueryService(
                stockOrderBookInstrumentRepository,
                stockPriceRepository,
                stockCorporateActionRepository,
                stockCorporateActionEntitlementRepository,
                cashDividendGuidanceSnapshotReader
        );
    }

    @Test
    void getGuidance_paidDividendHistory_returnsSplitAdjustedComparisons() {
        StockOrderBookInstrument instrument = instrument(1_000_000L, 900_000L);
        StockPrice price = priceWithPreviousClose("88500.00");
        StockCorporateAction latestDividend = dividend(
                4L,
                "2300.00",
                "99600.00",
                LocalDate.of(2026, 8, 16)
        );
        StockCorporateAction olderDividend = dividend(
                2L,
                "1000.00",
                "50000.00",
                LocalDate.of(2026, 5, 1)
        );
        StockCorporateAction split = split(
                1,
                5,
                LocalDate.of(2026, 6, 1)
        );
        StockCorporateActionEntitlementRepository.PaidCashAmountSummary latestSummary = paidCashSummary(4L, "2300000000.00");
        StockCorporateActionEntitlementRepository.PaidCashAmountSummary olderSummary = paidCashSummary(2L, "1000000000.00");
        when(stockOrderBookInstrumentRepository.findById("DEMO001")).thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("DEMO001")).thenReturn(Optional.of(price));
        when(stockCorporateActionRepository.findBySymbolOrderByCreatedAtDesc("DEMO001"))
                .thenReturn(List.of(latestDividend, split, olderDividend));
        when(stockCorporateActionEntitlementRepository.sumCashAmountByActionIdInAndStatus(
                List.of(4L, 2L),
                StockCorporateActionEntitlementStatus.PAID
        )).thenReturn(List.of(latestSummary, olderSummary));
        when(cashDividendGuidanceSnapshotReader.findLatestCompletedFullMarketHolding("DEMO001"))
                .thenReturn(Optional.of(new CashDividendGuidanceSnapshotReader.HoldingReference(
                        81L,
                        LocalDate.of(2026, 8, 20),
                        980_000L
                )));

        var response = service.getGuidance(" demo001 ");

        assertThat(response.history())
                .extracting(
                        history -> history.actionId(),
                        history -> history.splitAdjustedDividendPerShare(),
                        history -> history.dividendYield(),
                        history -> history.actualPaidCash(),
                        history -> history.eligibleShareQuantity()
                )
                .containsExactly(
                        tuple(4L, new BigDecimal("2300.00"), new BigDecimal("2.3092"), new BigDecimal("2300000000.00"), 1_000_000L),
                        tuple(2L, new BigDecimal("200.00"), new BigDecimal("2.0000"), new BigDecimal("1000000000.00"), 1_000_000L)
                );
        assertThat(List.of(
                response.recentHoldingQuantity(),
                response.holdingReferenceCloseRunId(),
                response.holdingReferenceBusinessDate()
        )).containsExactly(980_000L, 81L, LocalDate.of(2026, 8, 20));
    }

    @Test
    void getGuidance_previousCloseAvailable_usesStableCloseReference() {
        StockOrderBookInstrument instrument = instrument(1_000_000L, 1_000_000L);
        StockPrice price = priceWithPreviousClose("88500.00");
        when(stockOrderBookInstrumentRepository.findById("DEMO001"))
                .thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("DEMO001"))
                .thenReturn(Optional.of(price));
        when(stockCorporateActionRepository.findBySymbolOrderByCreatedAtDesc("DEMO001"))
                .thenReturn(List.of());

        var response = service.getGuidance("DEMO001");

        assertThat(List.of(response.referencePriceBasis(), response.referencePrice(), response.completedDividendCount()))
                .containsExactly("PREVIOUS_CLOSE", new BigDecimal("88500.00"), 0L);
    }

    @Test
    void getGuidance_previousCloseUnavailable_usesCurrentPriceFallback() {
        StockOrderBookInstrument instrument = instrument(1_000_000L, 1_000_000L);
        StockPrice price = mock(StockPrice.class);
        when(price.getCurrentPrice()).thenReturn(new BigDecimal("91800.00"));
        when(stockOrderBookInstrumentRepository.findById("DEMO001"))
                .thenReturn(Optional.of(instrument));
        when(stockPriceRepository.findById("DEMO001")).thenReturn(Optional.of(price));
        when(stockCorporateActionRepository.findBySymbolOrderByCreatedAtDesc("DEMO001"))
                .thenReturn(List.of());

        var response = service.getGuidance("DEMO001");

        assertThat(List.of(response.referencePriceBasis(), response.referencePrice()))
                .containsExactly("CURRENT_PRICE", new BigDecimal("91800.00"));
    }

    @Test
    void getGuidance_unknownSymbol_throwsNotFoundBeforePriceLookup() {
        when(stockOrderBookInstrumentRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getGuidance("unknown"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown order book symbol: UNKNOWN");

        verifyNoInteractions(
                stockPriceRepository,
                stockCorporateActionRepository,
                stockCorporateActionEntitlementRepository,
                cashDividendGuidanceSnapshotReader
        );
    }

    private StockOrderBookInstrument instrument(long issuedShares, long tradableShares) {
        StockOrderBookInstrument instrument = mock(StockOrderBookInstrument.class);
        when(instrument.getIssuedShares()).thenReturn(issuedShares);
        when(instrument.getTradableShares()).thenReturn(tradableShares);
        return instrument;
    }

    private StockPrice priceWithPreviousClose(String previousClose) {
        StockPrice price = mock(StockPrice.class);
        when(price.getPreviousClose()).thenReturn(new BigDecimal(previousClose));
        return price;
    }

    private StockCorporateAction dividend(long id, String amount, String basePrice, LocalDate exRightsDate) {
        StockCorporateAction action = mock(StockCorporateAction.class);
        when(action.getId()).thenReturn(id);
        when(action.getActionType()).thenReturn(StockCorporateActionType.CASH_DIVIDEND);
        when(action.getStatus()).thenReturn(StockCorporateActionStatus.PAID);
        when(action.getDividendAmount()).thenReturn(new BigDecimal(amount));
        when(action.getBasePrice()).thenReturn(new BigDecimal(basePrice));
        when(action.getExRightsDate()).thenReturn(exRightsDate);
        when(action.getPaymentDate()).thenReturn(exRightsDate.plusDays(1));
        return action;
    }

    private StockCorporateAction split(int splitFrom, int splitTo, LocalDate listingDate) {
        StockCorporateAction action = mock(StockCorporateAction.class);
        when(action.getActionType()).thenReturn(StockCorporateActionType.STOCK_SPLIT);
        when(action.getStatus()).thenReturn(StockCorporateActionStatus.LISTED);
        when(action.getSplitFrom()).thenReturn(splitFrom);
        when(action.getSplitTo()).thenReturn(splitTo);
        when(action.getListingDate()).thenReturn(listingDate);
        return action;
    }

    private StockCorporateActionEntitlementRepository.PaidCashAmountSummary paidCashSummary(long actionId, String amount) {
        StockCorporateActionEntitlementRepository.PaidCashAmountSummary summary =
                mock(StockCorporateActionEntitlementRepository.PaidCashAmountSummary.class);
        when(summary.getActionId()).thenReturn(actionId);
        when(summary.getCashAmount()).thenReturn(new BigDecimal(amount));
        when(summary.getEligibleShareQuantity()).thenReturn(1_000_000L);
        return summary;
    }
}
