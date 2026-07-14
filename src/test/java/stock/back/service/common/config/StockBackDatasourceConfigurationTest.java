package stock.back.service.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class StockBackDatasourceConfigurationTest {

    @Test
    void rootConfiguration_definesJdbcTemplateQueryTimeout() throws IOException {
        PropertySource<?> properties = loadProperties("application.yml");

        assertThat(properties.getProperty("spring.jdbc.template.query-timeout"))
                .isEqualTo("${STOCK_JDBC_QUERY_TIMEOUT:30s}");
    }

    @Test
    void rootConfiguration_definesDedicatedPriceStreamExecutor() throws IOException {
        PropertySource<?> properties = loadProperties("application.yml");

        assertThat(properties.getProperty("stock.market.price-stream.redis-listener-enabled"))
                .isEqualTo("${STOCK_PRICE_STREAM_REDIS_LISTENER_ENABLED:true}");
        assertThat(properties.getProperty("stock.market.price-stream.executor.core-size"))
                .isEqualTo("${STOCK_PRICE_STREAM_EXECUTOR_CORE_SIZE:1}");
        assertThat(properties.getProperty("stock.market.price-stream.executor.max-size"))
                .isEqualTo("${STOCK_PRICE_STREAM_EXECUTOR_MAX_SIZE:2}");
        assertThat(properties.getProperty("stock.market.price-stream.executor.queue-capacity"))
                .isEqualTo("${STOCK_PRICE_STREAM_EXECUTOR_QUEUE_CAPACITY:1000}");
    }

    @Test
    void localDatasource_usesShortLivedKeepalivePoolsAndNetworkTimeouts() throws IOException {
        PropertySource<?> properties = loadProperties("application-local.yml");

        assertProfileDatasource(properties, "database.datasource.pub.master", "stock-back-master-pool");
        assertProfileDatasource(properties, "database.datasource.pub.slave1", "stock-back-slave-pool");
    }

    @Test
    void devDatasource_usesShortLivedKeepalivePoolsAndNetworkTimeouts() throws IOException {
        PropertySource<?> properties = loadProperties("application-dev.yml");

        assertProfileDatasource(properties, "database.datasource.pub.master", "stock-back-master-pool");
        assertProfileDatasource(properties, "database.datasource.pub.slave1", "stock-back-slave-pool");
    }

    @Test
    void prodDatasource_keepsTimeoutsAndPoolDefaultsOverridable() throws IOException {
        PropertySource<?> properties = loadProperties("application-prod.yml");

        assertThat(properties.getProperty("database.datasource.pub.master.url").toString())
                .contains("connectTimeout=5000")
                .contains("socketTimeout=30000")
                .contains("tcpKeepAlive=true");
        assertThat(properties.getProperty("database.datasource.pub.master.configure.pool-name"))
                .isEqualTo("${STOCK_DB_POOL_NAME:stock-back-master-pool}");
        assertThat(properties.getProperty("database.datasource.pub.master.configure.max-lifetime"))
                .isEqualTo("${STOCK_DB_MAX_LIFETIME:300000}");
        assertThat(properties.getProperty("database.datasource.pub.master.configure.keepalive-time"))
                .isEqualTo("${STOCK_DB_KEEPALIVE_TIME:30000}");

        assertThat(properties.getProperty("database.datasource.pub.slave1.url").toString())
                .contains("connectTimeout=5000")
                .contains("socketTimeout=30000")
                .contains("tcpKeepAlive=true");
        assertThat(properties.getProperty("database.datasource.pub.slave1.configure.pool-name"))
                .isEqualTo("${STOCK_DB_SLAVE_POOL_NAME:stock-back-slave-pool}");
        assertThat(properties.getProperty("database.datasource.pub.slave1.configure.max-lifetime"))
                .isEqualTo("${STOCK_DB_SLAVE_MAX_LIFETIME:${STOCK_DB_MAX_LIFETIME:300000}}");
        assertThat(properties.getProperty("database.datasource.pub.slave1.configure.keepalive-time"))
                .isEqualTo("${STOCK_DB_SLAVE_KEEPALIVE_TIME:${STOCK_DB_KEEPALIVE_TIME:30000}}");
    }

    private void assertProfileDatasource(PropertySource<?> properties, String prefix, String poolName) {
        assertThat(properties.getProperty(prefix + ".url").toString())
                .contains("connectTimeout=5000")
                .contains("socketTimeout=30000")
                .contains("tcpKeepAlive=true");
        assertThat(properties.getProperty(prefix + ".configure.pool-name")).isEqualTo(poolName);
        assertThat(properties.getProperty(prefix + ".configure.minimum-idle")).isEqualTo(1);
        assertThat(properties.getProperty(prefix + ".configure.validation-timeout")).isEqualTo(5000);
        assertThat(properties.getProperty(prefix + ".configure.idle-timeout")).isEqualTo(240000);
        assertThat(properties.getProperty(prefix + ".configure.max-lifetime")).isEqualTo(300000);
        assertThat(properties.getProperty(prefix + ".configure.keepalive-time")).isEqualTo(30000);
    }

    private PropertySource<?> loadProperties(String resourceName) throws IOException {
        return new YamlPropertySourceLoader()
                .load(resourceName, new ClassPathResource(resourceName))
                .get(0);
    }
}
