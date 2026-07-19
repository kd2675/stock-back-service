package stock.back.service.market.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import web.common.core.simulation.SimulationMarketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketSessionFenceCommandServiceTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private MarketSessionFenceCommandService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SimulationMarketSessionService sessionService = mock(SimulationMarketSessionService.class);
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
        service = new MarketSessionFenceCommandService(JdbcClient.create(jdbcTemplate), sessionService);
        jdbcTemplate.execute("drop all objects");
        jdbcTemplate.execute(
                """
                create table stock_market_business_state (
                    state_id varchar(20) primary key,
                    active_business_date date not null,
                    preparing_business_date date,
                    raw_simulation_date date not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_market_session_fence (
                    market_type varchar(20) not null,
                    symbol varchar(20) not null,
                    business_date date not null,
                    session_epoch bigint not null,
                    session_state varchar(20) not null,
                    state_changed_at timestamp not null,
                    version bigint not null,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    primary key (market_type, symbol)
                )
                """
        );
        LocalDateTime seededAt = LocalDateTime.of(2026, 7, 3, 6, 0);
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date, raw_simulation_date
                ) values ('DEFAULT', ?, null, ?)
                """,
                LocalDate.of(2026, 7, 3),
                LocalDate.of(2026, 7, 3)
        );
        jdbcTemplate.update(
                """
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_epoch, session_state,
                    state_changed_at, version, created_at, updated_at
                ) values ('ORDER_BOOK', 'DEMO001', ?, 7, 'OPEN', ?, 0, ?, ?)
                """,
                LocalDate.of(2026, 7, 3),
                seededAt,
                seededAt,
                seededAt
        );
    }

    @Test
    void synchronize_haltTransition_closesFenceAndIncrementsEpochOnce() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 7, 3, 10, 0);

        transactionTemplate.executeWithoutResult(status -> service.synchronize(
                MarketType.ORDER_BOOK,
                "DEMO001",
                true,
                MarketSessionStatus.HALTED,
                changedAt
        ));

        assertThat(loadFence()).isEqualTo(new FenceState("CLOSED", 8L));
    }

    @Test
    void synchronize_repeatedClosedState_doesNotChurnEpoch() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 7, 3, 10, 0);
        transactionTemplate.executeWithoutResult(status -> service.synchronize(
                MarketType.ORDER_BOOK,
                "DEMO001",
                true,
                MarketSessionStatus.HALTED,
                changedAt
        ));

        transactionTemplate.executeWithoutResult(status -> service.synchronize(
                MarketType.ORDER_BOOK,
                "DEMO001",
                false,
                MarketSessionStatus.CLOSED,
                changedAt.plusMinutes(1)
        ));

        assertThat(loadFence()).isEqualTo(new FenceState("CLOSED", 8L));
    }

    @Test
    void synchronize_openWhilePreparingDateExists_failsClosed() {
        jdbcTemplate.update(
                "update stock_market_business_state set preparing_business_date = date '2026-07-04'"
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> service.synchronize(
                MarketType.ORDER_BOOK,
                "DEMO001",
                true,
                MarketSessionStatus.OPEN,
                LocalDateTime.of(2026, 7, 3, 10, 0)
        ))).hasMessageContaining("ready");
    }

    private FenceState loadFence() {
        return jdbcTemplate.queryForObject(
                """
                select session_state, session_epoch
                  from stock_market_session_fence
                 where market_type = 'ORDER_BOOK' and symbol = 'DEMO001'
                """,
                (rs, rowNum) -> new FenceState(
                        rs.getString("session_state"),
                        rs.getLong("session_epoch")
                )
        );
    }

    private record FenceState(String state, long epoch) {
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:market_session_fence_command;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
