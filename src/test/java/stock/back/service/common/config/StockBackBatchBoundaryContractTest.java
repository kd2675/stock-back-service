package stock.back.service.common.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockBackBatchBoundaryContractTest {

    private static final Path MAIN_JAVA_ROOT = Path.of("src/main/java");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
    private static final Path DEV_APPLICATION_YML = Path.of("src/main/resources/application-dev.yml");
    private static final Path PROD_APPLICATION_YML = Path.of("src/main/resources/application-prod.yml");
    private static final List<String> FORBIDDEN_BATCH_IMPLEMENTATION_MARKERS = List.of(
            "import stock.batch.service.",
            "stock.batch.service."
    );
    private static final List<String> FORBIDDEN_BATCH_OPERATION_TABLE_MARKERS = List.of(
            "stock_batch_job_control",
            "stock_batch_job_lock"
    );
    private static final List<String> FORBIDDEN_SCHEDULER_MARKERS = List.of(
            "@Scheduled",
            "@EnableScheduling",
            "TaskScheduler",
            "ScheduledExecutorService"
    );

    @Test
    void stockBackJavaCode_doesNotDependOnStockBatchImplementationPackage() throws IOException {
        assertThat(filesContaining(FORBIDDEN_BATCH_IMPLEMENTATION_MARKERS))
                .as("stock-back must call stock-batch through internal HTTP API, not direct Java implementation imports")
                .isEmpty();
    }

    @Test
    void stockBackJavaCode_doesNotReadOrWriteBatchOperationTablesDirectly() throws IOException {
        assertThat(filesContaining(FORBIDDEN_BATCH_OPERATION_TABLE_MARKERS))
                .as("stock_batch_job_control and stock_batch_job_lock are owned by stock-batch runtime control")
                .isEmpty();
    }

    @Test
    void stockBackJavaCode_doesNotOwnTimeBasedBatchScheduling() throws IOException {
        assertThat(filesContaining(FORBIDDEN_SCHEDULER_MARKERS))
                .as("time-based stock state changes must be scheduled in stock-batch-service")
                .isEmpty();
    }

    @Test
    void prodProfile_requiresExplicitStockBatchHttpBoundaryConfiguration() throws IOException {
        String prodConfig = Files.readString(PROD_APPLICATION_YML, StandardCharsets.UTF_8);

        assertRequiresExplicitStockBatchHttpBoundaryConfiguration(prodConfig);
    }

    @Test
    void devProfile_requiresExplicitStockBatchHttpBoundaryConfiguration() throws IOException {
        String devConfig = Files.readString(DEV_APPLICATION_YML, StandardCharsets.UTF_8);

        assertRequiresExplicitStockBatchHttpBoundaryConfiguration(devConfig);
    }

    @Test
    void defaultProfile_doesNotAllowEmptyStockBatchInternalToken() throws IOException {
        String applicationConfig = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);

        assertThat(applicationConfig).contains("internal-token: ${STOCK_BATCH_INTERNAL_TOKEN:local-stock-batch-internal-token}");
        assertThat(applicationConfig).doesNotContain("internal-token: ${STOCK_BATCH_INTERNAL_TOKEN:}");
    }

    @Test
    void defaultProfile_usesLocalDirectStockBatchPort() throws IOException {
        String applicationConfig = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
        String stockBatchAdminClient = Files.readString(
                MAIN_JAVA_ROOT.resolve("stock/back/service/market/client/StockBatchAdminClient.java"),
                StandardCharsets.UTF_8
        );

        assertThat(applicationConfig).contains("base-url: ${STOCK_BATCH_API_BASE_URL:http://localhost:20481}");
        assertThat(applicationConfig).doesNotContain("base-url: ${STOCK_BATCH_API_BASE_URL:http://localhost:30481}");
        assertThat(stockBatchAdminClient).contains("@Value(\"${stock.batch-client.base-url:http://localhost:20481}\")");
        assertThat(stockBatchAdminClient).doesNotContain("@Value(\"${stock.batch-client.base-url:http://localhost:30481}\")");
    }

    private void assertRequiresExplicitStockBatchHttpBoundaryConfiguration(String config) {
        assertThat(config).contains("base-url: ${STOCK_BATCH_API_BASE_URL}");
        assertThat(config).contains("internal-token: ${STOCK_BATCH_INTERNAL_TOKEN}");
        assertThat(config).doesNotContain("base-url: ${STOCK_BATCH_API_BASE_URL:");
        assertThat(config).doesNotContain("internal-token: ${STOCK_BATCH_INTERNAL_TOKEN:");
    }

    private List<Path> filesContaining(List<String> markers) throws IOException {
        try (var paths = Files.walk(MAIN_JAVA_ROOT)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> containsAny(path, markers))
                    .sorted()
                    .toList();
        }
    }

    private boolean containsAny(Path path, List<String> markers) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return markers.stream().anyMatch(source::contains);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + path, ex);
        }
    }
}
