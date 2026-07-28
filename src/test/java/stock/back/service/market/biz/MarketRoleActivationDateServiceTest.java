package stock.back.service.market.biz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketRoleActivationDateServiceTest {

    private static final LocalDate ACTIVE_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDate PREPARING_DATE = ACTIVE_DATE.plusDays(1);

    private JdbcTemplate jdbcTemplate;
    private SimulationMarketSessionService marketSessionService;
    private MarketRoleActivationDateService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:market_role_activation_date_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new FileSystemResource(batchH2Ddl())).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        marketSessionService = mock(SimulationMarketSessionService.class);
        service = new MarketRoleActivationDateService(
                JdbcClient.create(dataSource),
                marketSessionService
        );
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date,
                    raw_simulation_date, version, created_at, updated_at
                ) values ('DEFAULT', ?, ?, ?, 1, ?, ?)
                """,
                ACTIVE_DATE,
                PREPARING_DATE,
                PREPARING_DATE,
                ACTIVE_DATE.atStartOfDay(),
                ACTIVE_DATE.atStartOfDay()
        );
    }

    @Test
    void resolveNextOpeningDate_regularSession_usesFollowingBusinessDate() {
        when(marketSessionService.currentSession())
                .thenReturn(SimulationMarketSession.REGULAR);

        assertThat(service.resolveNextOpeningDate(
                clock(ACTIVE_DATE.atTime(10, 0)),
                ACTIVE_DATE
        )).isEqualTo(PREPARING_DATE);
    }

    @Test
    void resolveNextOpeningDate_preOpenBeforeAutoMarketPreparation_usesPreparingDate() {
        when(marketSessionService.currentSession())
                .thenReturn(SimulationMarketSession.PRE_OPEN);
        insertCycle("MARKET_DATA_PREPARED");

        assertThat(service.resolveNextOpeningDate(
                clock(PREPARING_DATE.atTime(5, 0)),
                ACTIVE_DATE
        )).isEqualTo(PREPARING_DATE);
    }

    @Test
    void resolveNextOpeningDate_preOpenAfterAutoMarketPreparation_defersOneDay() {
        when(marketSessionService.currentSession())
                .thenReturn(SimulationMarketSession.PRE_OPEN);
        insertCycle("AUTO_MARKET_PREPARED");

        assertThat(service.resolveNextOpeningDate(
                clock(PREPARING_DATE.atTime(5, 40)),
                ACTIVE_DATE
        )).isEqualTo(PREPARING_DATE.plusDays(1));
    }

    @Test
    void resolveNextOpeningDate_autoMarketPreparationAlreadyClaimed_defersOneDay() {
        when(marketSessionService.currentSession())
                .thenReturn(SimulationMarketSession.PRE_OPEN);
        insertCycle("MARKET_DATA_PREPARED", "RUNNING");

        assertThat(service.resolveNextOpeningDate(
                clock(PREPARING_DATE.atTime(5, 0)),
                ACTIVE_DATE
        )).isEqualTo(PREPARING_DATE.plusDays(1));
    }

    private void insertCycle(String phase) {
        insertCycle(phase, "PENDING");
    }

    private void insertCycle(String phase, String status) {
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(
                    business_date, scope_type, scope_key, phase,
                    status, created_at, updated_at
                ) values (?, 'FULL_MARKET', 'ALL', ?, ?, ?, ?)
                """,
                ACTIVE_DATE,
                phase,
                status,
                ACTIVE_DATE.atStartOfDay(),
                ACTIVE_DATE.atStartOfDay()
        );
    }

    private SimulationClockSnapshot clock(LocalDateTime simulationDateTime) {
        return new SimulationClockSnapshot(
                simulationDateTime.toLocalDate(),
                simulationDateTime,
                ACTIVE_DATE.atStartOfDay(),
                simulationDateTime,
                ACTIVE_DATE.atStartOfDay(),
                7_200,
                true,
                false,
                0L,
                null,
                null
        );
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
