package stock.back.service.market.biz;

import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.market.vo.OrderBookInstrumentResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;

final class OrderBookInstrumentResponseMapper {

    private OrderBookInstrumentResponseMapper() {
    }

    static OrderBookInstrumentResponse toResponse(StockOrderBookInstrument instrument, StockPrice price) {
        BigDecimal currentPrice = price == null ? instrument.getInitialPrice() : price.getCurrentPrice();
        BigDecimal priceLimitBase = price == null ? instrument.getInitialPrice() : price.getPreviousClose();
        LocalDateTime priceTime = price == null ? instrument.getUpdatedAt() : price.getPriceTime();
        String priceProvider = price == null ? "order-book-initial" : price.getProvider();
        return new OrderBookInstrumentResponse(
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getMarket(),
                instrument.getInitialPrice(),
                instrument.getIssuedShares(),
                instrument.getTradableShares(),
                KoreanStockTickSizePolicy.tickSizeForCurrentPrice(instrument.getMarket(), currentPrice),
                instrument.getPriceLimitRate(),
                priceLimitBase,
                currentPrice,
                priceTime,
                priceProvider,
                Boolean.TRUE.equals(instrument.getEnabled()),
                instrument.getCreatedAt(),
                instrument.getUpdatedAt()
        );
    }
}
