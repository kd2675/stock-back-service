package stock.back.service.common.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StockSchemaReadinessValidatorTest {

    @Test
    void run_completeCanonicalH2Schema_passesAllEodRequirements() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:back_schema_readiness_complete;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new FileSystemResource(batchH2Ddl())).execute(dataSource);
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> buildPropertiesProvider = mock(ObjectProvider.class);
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                buildPropertiesProvider,
                "2026-07-19-eod-v2"
        );

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void run_missingEodSchema_failsClosedBeforeApiAcceptsRequests() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:back_schema_readiness_missing;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> buildPropertiesProvider = mock(ObjectProvider.class);
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                buildPropertiesProvider,
                "2026-07-19-eod-v2"
        );

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stock EOD schema is not ready")
                .hasMessageContaining("stock_market_session_fence");
    }

    private Path batchH2Ddl() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path moduleRelative = workingDirectory
                .resolve("../stock-batch-service/src/main/resources/db/ddl/stock_h2.sql")
                .normalize();
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }

        Path rootRelative = workingDirectory
                .resolve("stock-batch-service/src/main/resources/db/ddl/stock_h2.sql")
                .normalize();
        assertThat(rootRelative).isRegularFile();
        return rootRelative;
    }
}
