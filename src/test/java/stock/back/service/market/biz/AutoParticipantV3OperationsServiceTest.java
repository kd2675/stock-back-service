package stock.back.service.market.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import stock.back.service.market.vo.AutoParticipantV3RuntimeRequest;
import web.common.core.simulation.SimulationClockSnapshot;

class AutoParticipantV3OperationsServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = TRADE_DATE.atTime(10, 0);

    private JdbcTemplate jdbcTemplate;
    private SimulationClockService simulationClockService;
    private AutoParticipantV3OperationsService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:v3_operations_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(
                new FileSystemResource(batchH2Ddl())
        ).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        simulationClockService = org.mockito.Mockito.mock(SimulationClockService.class);
        when(simulationClockService.currentSnapshot()).thenReturn(
                new SimulationClockSnapshot(
                        TRADE_DATE,
                        NOW,
                        TRADE_DATE.atStartOfDay(),
                        NOW,
                        TRADE_DATE.atStartOfDay(),
                        7_200,
                        true,
                        false,
                        0L,
                        null,
                        null
                )
        );
        service = new AutoParticipantV3OperationsService(
                jdbcTemplate,
                simulationClockService
        );
    }

    @Test
    void getOperations_emptyTradeDateStillExposesActiveV3Policy() {
        var operations = service.getOperations();

        assertThat(operations.simulationTradeDate()).isEqualTo(TRADE_DATE);
        assertThat(operations.policies()).hasSize(1);
        assertThat(operations.policies().getFirst().status()).isEqualTo("ACTIVE");
        assertThat(operations.policies().getFirst().policyJson()).contains("\"model\":\"V3\"");
        assertThat(operations.dailySummary().accountCount()).isZero();
        assertThat(operations.incompleteLiquidationPlanCount()).isZero();
    }

    @Test
    void updateRuntime_requiresReasonAndMutatesOnlyActiveRuntimeFlag() {
        assertThatThrownBy(() -> service.updateRuntime(
                new AutoParticipantV3RuntimeRequest(false, " "),
                "admin"
        )).hasMessageContaining("reason");

        service.updateRuntime(
                new AutoParticipantV3RuntimeRequest(false, "Emergency stop"),
                "admin"
        );

        assertThat(jdbcTemplate.queryForObject(
                """
                select runtime_enabled
                  from stock_auto_participant_policy_revision
                 where status = 'ACTIVE'
                """,
                Boolean.class
        )).isFalse();
    }

    private Path batchH2Ddl() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path moduleRelative = workingDirectory
                .resolve("../stock-batch-service/src/main/resources/db/ddl/stock_h2.sql")
                .normalize();
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        return workingDirectory
                .resolve("stock-batch-service/src/main/resources/db/ddl/stock_h2.sql")
                .normalize();
    }
}
