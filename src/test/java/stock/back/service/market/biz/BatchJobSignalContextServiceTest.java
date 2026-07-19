package stock.back.service.market.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchJobSignalContextServiceTest {

    @Test
    void resolveSymbol_loadsActiveBusinessDateFenceEpochAndLogicalCycleInOneContext() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        LocalDateTime simulationNow = LocalDateTime.of(2026, 7, 15, 18, 30);
        SimulationClockService clockService = mock(SimulationClockService.class);
        when(clockService.currentMarketDateTime()).thenReturn(simulationNow);
        BatchJobSignalContextService service = new BatchJobSignalContextService(
                JdbcClient.create(jdbcTemplate),
                clockService
        );

        var context = service.resolveSymbol(" demo001 ");

        assertThat(context).isEqualTo(new BatchJobSignalContextService.BatchJobSignalContext(
                LocalDate.of(2026, 7, 15),
                19L,
                701L,
                simulationNow
        ));
    }

    private JdbcTemplate createJdbcTemplate() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource());
        jdbcTemplate.execute("""
                create table stock_market_business_state (
                    state_id varchar(20) primary key,
                    active_business_date date not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_market_session_fence (
                    market_type varchar(20) not null,
                    symbol varchar(20) not null,
                    business_date date not null,
                    session_epoch bigint not null,
                    primary key (market_type, symbol)
                )
                """);
        jdbcTemplate.execute("""
                create table stock_post_close_cycle (
                    id bigint primary key,
                    business_date date not null,
                    scope_type varchar(20) not null,
                    scope_key varchar(40) not null
                )
                """);
        jdbcTemplate.update(
                "insert into stock_market_business_state(state_id, active_business_date) values ('DEFAULT', ?)",
                LocalDate.of(2026, 7, 15)
        );
        jdbcTemplate.update(
                """
                insert into stock_market_session_fence(market_type, symbol, business_date, session_epoch)
                values ('ORDER_BOOK', 'DEMO001', ?, 19)
                """,
                LocalDate.of(2026, 7, 15)
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(id, business_date, scope_type, scope_key)
                values (701, ?, 'SYMBOL', 'DEMO001')
                """,
                LocalDate.of(2026, 7, 15)
        );
        return jdbcTemplate;
    }

    private DataSource createDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:batch_signal_context_%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
                .formatted(UUID.randomUUID()));
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
