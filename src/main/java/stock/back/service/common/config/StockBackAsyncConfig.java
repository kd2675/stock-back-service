package stock.back.service.common.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class StockBackAsyncConfig {

    @Bean(name = StockBackExecutorNames.PRICE_STREAM)
    public ThreadPoolTaskExecutor stockBackPriceStreamTaskExecutor(
            @Value("${stock.market.price-stream.executor.core-size:1}") int coreSize,
            @Value("${stock.market.price-stream.executor.max-size:2}") int maxSize,
            @Value("${stock.market.price-stream.executor.queue-capacity:1000}") int queueCapacity
    ) {
        if (coreSize <= 0) {
            throw new IllegalArgumentException("price stream executor coreSize must be positive");
        }
        if (maxSize < coreSize) {
            throw new IllegalArgumentException("price stream executor maxSize must be greater than or equal to coreSize");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("price stream executor queueCapacity must not be negative");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("stock-back-price-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
