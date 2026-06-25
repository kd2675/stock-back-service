package stock.back.service.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDirectConfigurationTest {

    @Test
    void localDirectProfile_disablesDiscoveryAndUsesDirectAuthUrl() throws IOException {
        PropertySource<?> properties = loadLocalDirectProperties();

        assertThat(properties.getProperty("spring.cloud.discovery.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("spring.cloud.service-registry.auto-registration.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("eureka.client.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("eureka.client.registerWithEureka")).isEqualTo(false);
        assertThat(properties.getProperty("eureka.client.fetchRegistry")).isEqualTo(false);
        assertThat(properties.getProperty("spring.cloud.openfeign.client.config.auth-back-server.url"))
                .isEqualTo("${STOCK_AUTH_BASE_URL:http://localhost:9000}");
    }

    @Test
    void localDirectProfile_allowsStockFrontOriginForDirectBrowserCalls() throws IOException {
        PropertySource<?> properties = loadLocalDirectProperties();

        assertThat(properties.getProperty("stock.cors.allowed-origins"))
                .isEqualTo("${STOCK_CORS_ALLOWED_ORIGINS:http://localhost:3005,http://127.0.0.1:3005}");
    }

    @Test
    void localDirectProfile_usesDirectStockBatchHttpBoundary() throws IOException {
        PropertySource<?> properties = loadLocalDirectProperties();

        assertThat(properties.getProperty("stock.batch-client.base-url"))
                .isEqualTo("${STOCK_BATCH_API_BASE_URL:http://localhost:20481}");
        assertThat(properties.getProperty("stock.batch-client.internal-token"))
                .isEqualTo("${STOCK_BATCH_INTERNAL_TOKEN:local-stock-batch-internal-token}");
    }

    private PropertySource<?> loadLocalDirectProperties() throws IOException {
        return new YamlPropertySourceLoader()
                .load("local-direct", new ClassPathResource("application-local-direct.yml"))
                .get(0);
    }
}
