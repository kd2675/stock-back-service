package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import stock.back.service.database.entity.StockInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketSeedServiceTest {

    private StockInstrumentRepository stockInstrumentRepository;
    private StockPriceRepository stockPriceRepository;
    private MarketSeedService marketSeedService;

    @BeforeEach
    void setUp() {
        stockInstrumentRepository = mock(StockInstrumentRepository.class);
        stockPriceRepository = mock(StockPriceRepository.class);
        marketSeedService = new MarketSeedService(stockInstrumentRepository, stockPriceRepository);
    }

    @Test
    void run_emptyMarketData_seedsDefaultInstrumentsAndPrices() {
        ArgumentCaptor<StockInstrument> instrumentCaptor = ArgumentCaptor.forClass(StockInstrument.class);
        ArgumentCaptor<StockPrice> priceCaptor = ArgumentCaptor.forClass(StockPrice.class);

        marketSeedService.run();

        verify(stockInstrumentRepository, times(5)).save(instrumentCaptor.capture());
        verify(stockPriceRepository, times(5)).save(priceCaptor.capture());
        assertThat(instrumentCaptor.getAllValues())
                .extracting(StockInstrument::getSymbol, StockInstrument::getName, StockInstrument::getMarket, StockInstrument::getEnabled)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("005930", "삼성전자", "KOSPI", true),
                        org.assertj.core.groups.Tuple.tuple("000660", "SK하이닉스", "KOSPI", true),
                        org.assertj.core.groups.Tuple.tuple("035420", "NAVER", "KOSPI", true),
                        org.assertj.core.groups.Tuple.tuple("035720", "카카오", "KOSPI", true),
                        org.assertj.core.groups.Tuple.tuple("051910", "LG화학", "KOSPI", true)
                );
        assertThat(priceCaptor.getAllValues())
                .extracting(StockPrice::getSymbol, StockPrice::getCurrentPrice, StockPrice::getPreviousClose, StockPrice::getProvider)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("005930", new BigDecimal("72400"), new BigDecimal("72400"), "seed"),
                        org.assertj.core.groups.Tuple.tuple("000660", new BigDecimal("241500"), new BigDecimal("241500"), "seed"),
                        org.assertj.core.groups.Tuple.tuple("035420", new BigDecimal("193800"), new BigDecimal("193800"), "seed"),
                        org.assertj.core.groups.Tuple.tuple("035720", new BigDecimal("52100"), new BigDecimal("52100"), "seed"),
                        org.assertj.core.groups.Tuple.tuple("051910", new BigDecimal("312000"), new BigDecimal("312000"), "seed")
                );
    }

    @Test
    void run_existingMarketData_doesNotOverwriteDefaultRows() {
        when(stockInstrumentRepository.existsById(anyString())).thenReturn(true);
        when(stockPriceRepository.existsById(anyString())).thenReturn(true);

        marketSeedService.run();

        verify(stockInstrumentRepository, never()).save(org.mockito.ArgumentMatchers.any(StockInstrument.class));
        verify(stockPriceRepository, never()).save(org.mockito.ArgumentMatchers.any(StockPrice.class));
    }
}
