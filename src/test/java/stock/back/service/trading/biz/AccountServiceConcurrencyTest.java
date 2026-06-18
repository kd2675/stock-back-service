package stock.back.service.trading.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import stock.back.service.database.repository.StockAccountRepository;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AccountServiceConcurrencyTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private StockAccountRepository stockAccountRepository;

    @BeforeEach
    void setUp() {
        stockAccountRepository.deleteAll();
    }

    @Test
    void getOrOpenAccountForUpdate_concurrentFirstOpen_createsSingleAccount() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> openAccountAfterStart(start));
            Future<String> second = executor.submit(() -> openAccountAfterStart(start));

            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("race-open-user");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("race-open-user");
            assertThat(stockAccountRepository.findAll()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private String openAccountAfterStart(CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return accountService.getOrOpenAccountForUpdate("race-open-user").getUserKey();
    }
}
