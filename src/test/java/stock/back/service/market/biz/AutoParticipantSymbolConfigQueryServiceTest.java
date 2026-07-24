package stock.back.service.market.biz;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import stock.back.service.market.vo.AutoParticipantLifecycleScope;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class AutoParticipantSymbolConfigQueryServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AutoParticipantSymbolConfigQueryService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:auto_participant_symbol_config_query_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        service = new AutoParticipantSymbolConfigQueryService(
                new NamedParameterJdbcTemplate(dataSource)
        );
    }

    @Test
    void getAutoParticipantSymbolConfigs_withdrawnScope_returnsOnlyPersistedWithdrawnStrategies() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 10, 0);
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(user_key, withdrawn_at)
                values ('auto-current', null),
                       ('auto-withdrawn', ?)
                """,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_symbol_config(
                    user_key, symbol, enabled, intensity, updated_at
                ) values ('auto-current', 'DEMO001', true, 5, ?),
                         ('auto-withdrawn', 'DEMO001', false, 3, ?),
                         ('auto-withdrawn', 'DEMO002', true, 8, ?)
                """,
                now,
                now,
                now.plusMinutes(1)
        );

        List<AutoParticipantSymbolConfigResponse> result =
                service.getAutoParticipantSymbolConfigs(
                        AutoParticipantLifecycleScope.WITHDRAWN,
                        List.of()
                );

        assertThat(result)
                .extracting(
                        AutoParticipantSymbolConfigResponse::userKey,
                        AutoParticipantSymbolConfigResponse::symbol,
                        AutoParticipantSymbolConfigResponse::enabled,
                        AutoParticipantSymbolConfigResponse::intensity
                )
                .containsExactly(
                        tuple("auto-withdrawn", "DEMO001", false, 3),
                        tuple("auto-withdrawn", "DEMO002", true, 8)
                );
    }

    private void createSchema() {
        jdbcTemplate.execute(
                """
                create table stock_auto_participant(
                    user_key varchar(64) primary key,
                    withdrawn_at timestamp
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_symbol_config(
                    user_key varchar(64) not null,
                    symbol varchar(20) not null,
                    enabled boolean not null,
                    intensity int not null,
                    updated_at timestamp not null,
                    primary key(user_key, symbol)
                )
                """
        );
    }
}
