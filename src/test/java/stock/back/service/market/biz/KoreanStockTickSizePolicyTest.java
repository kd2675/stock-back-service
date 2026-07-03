package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanStockTickSizePolicyTest {

    @Test
    void tickSizeForQuotePrice_appliesKoreanStockPriceBands() {
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("1999.00"))).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("2000.00"))).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("5000.00"))).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("20000.00"))).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("50000.00"))).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("200000.00"))).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("500000.00"))).isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    void tickSizeForQuotePrice_keepsEtfEtnElwAtFiveWon() {
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ETF", new BigDecimal("700.00"))).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ETN", new BigDecimal("70000.00"))).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ELW", new BigDecimal("700000.00"))).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    void validQuotePrice_usesQuotePriceBandNotInstrumentFixedTick() {
        assertThat(KoreanStockTickSizePolicy.isValidQuotePrice("ORDERBOOK", new BigDecimal("1999.00"))).isTrue();
        assertThat(KoreanStockTickSizePolicy.isValidQuotePrice("ORDERBOOK", new BigDecimal("2001.00"))).isFalse();
        assertThat(KoreanStockTickSizePolicy.isValidQuotePrice("ORDERBOOK", new BigDecimal("2005.00"))).isTrue();
    }
}
