package stock.back.service.common.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StockBackDatasourceRuntimeBindingTest {

    @Test
    void pubDataSources_bindHikariPoolTimeoutsAndNames(
            @Qualifier("pubMasterDatasource") DataSource masterDataSource,
            @Qualifier("pubSlave1Datasource") DataSource slaveDataSource
    ) {
        assertHikariDataSource(masterDataSource, "stock-back-master-test-pool");
        assertHikariDataSource(slaveDataSource, "stock-back-slave-test-pool");
    }

    @Test
    void jdbcTemplates_bindGlobalQueryTimeout(
            @Autowired
            JdbcTemplate jdbcTemplate,
            @Autowired
            NamedParameterJdbcTemplate namedParameterJdbcTemplate
    ) {
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(30);
        assertThat(namedParameterJdbcTemplate.getJdbcTemplate().getQueryTimeout()).isEqualTo(30);
    }

    private void assertHikariDataSource(DataSource dataSource, String poolName) {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);

        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        assertThat(hikariDataSource.getPoolName()).isEqualTo(poolName);
        assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(4);
        assertThat(hikariDataSource.getMinimumIdle()).isEqualTo(1);
        assertThat(hikariDataSource.getConnectionTimeout()).isEqualTo(30_000);
        assertThat(hikariDataSource.getValidationTimeout()).isEqualTo(5_000);
        assertThat(hikariDataSource.getIdleTimeout()).isEqualTo(240_000);
        assertThat(hikariDataSource.getMaxLifetime()).isEqualTo(300_000);
        assertThat(hikariDataSource.getKeepaliveTime()).isEqualTo(120_000);
        assertThat(hikariDataSource.getKeepaliveTime()).isLessThan(hikariDataSource.getMaxLifetime());
        assertThat(hikariDataSource.getValidationTimeout()).isLessThan(hikariDataSource.getConnectionTimeout());
    }
}
