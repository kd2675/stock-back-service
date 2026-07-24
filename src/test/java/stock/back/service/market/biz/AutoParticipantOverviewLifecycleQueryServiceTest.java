package stock.back.service.market.biz;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import stock.back.service.market.vo.AutoParticipantActivityScope;
import stock.back.service.market.vo.AutoParticipantLifecycleScope;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoParticipantOverviewLifecycleQueryServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AutoParticipantOverviewQueryService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:auto_participant_overview_lifecycle_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 12, 0);
        when(simulationClockService.currentMarketDayStart()).thenReturn(now.toLocalDate().atStartOfDay());
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        service = new AutoParticipantOverviewQueryService(
                new NamedParameterJdbcTemplate(dataSource),
                mock(AutoMarketStatusDataLoader.class),
                mock(AutoParticipantHoldingQueryService.class),
                mock(AutoParticipantProfileOverviewQueryService.class),
                simulationClockService,
                new SimpleMeterRegistry()
        );
    }

    @Test
    void getAutoParticipantOverviews_withdrawnScope_excludesCurrentParticipants() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 12, 0);
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(
                    user_key, display_name, enabled, profile_type,
                    created_at, updated_at, withdrawn_at
                ) values ('auto-current', '현재 참여자', true, 'NOISE_TRADER', ?, ?, null),
                         ('auto-withdrawn', '휴면 참여자', false, 'CONTRARIAN', ?, ?, ?)
                """,
                now.minusDays(2),
                now.minusDays(1),
                now.minusDays(3),
                now.minusHours(1),
                now.minusHours(1)
        );

        List<AutoParticipantOverviewResponse> result = service.getAutoParticipantOverviews(
                false,
                List.of(),
                AutoParticipantActivityScope.ALL,
                AutoParticipantLifecycleScope.WITHDRAWN
        );

        assertThat(result)
                .extracting(
                        AutoParticipantOverviewResponse::userKey,
                        AutoParticipantOverviewResponse::displayName,
                        AutoParticipantOverviewResponse::withdrawnAt
                )
                .containsExactly(
                        tuple("auto-withdrawn", "휴면 참여자", now.minusHours(1))
                );
    }

    private void createSchema() {
        jdbcTemplate.execute(
                """
                create table stock_auto_participant(
                    user_key varchar(64) primary key,
                    display_name varchar(80) not null,
                    enabled boolean not null,
                    profile_type varchar(40) not null,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    withdrawn_at timestamp
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_account(
                    id bigint primary key,
                    user_key varchar(64),
                    status varchar(20),
                    cash_balance decimal(19,2)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_symbol_config(
                    user_key varchar(64) not null,
                    symbol varchar(20) not null,
                    enabled boolean not null
                )
                """
        );
    }
}
