package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.StockInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class MarketSeedService implements CommandLineRunner {

    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;

    @Override
    @Transactional
    public void run(String... args) {
        seedInstrument("005930", "삼성전자", "KOSPI", "72400");
        seedInstrument("000660", "SK하이닉스", "KOSPI", "241500");
        seedInstrument("035420", "NAVER", "KOSPI", "193800");
        seedInstrument("035720", "카카오", "KOSPI", "52100");
        seedInstrument("051910", "LG화학", "KOSPI", "312000");
    }

    private void seedInstrument(String symbol, String name, String market, String price) {
        if (!stockInstrumentRepository.existsById(symbol)) {
            stockInstrumentRepository.save(StockInstrument.listed(symbol, name, market));
        }
        if (!stockPriceRepository.existsById(symbol)) {
            stockPriceRepository.save(StockPrice.initial(symbol, new BigDecimal(price)));
        }
    }
}
