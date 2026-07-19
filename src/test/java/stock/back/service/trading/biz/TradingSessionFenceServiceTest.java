package stock.back.service.trading.biz;

import java.time.LocalDate;
import java.time.LocalTime;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.market.biz.SimulationMarketSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradingSessionFenceServiceTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private TradingSessionFenceService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = createDataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SimulationMarketSessionService sessionService = mock(SimulationMarketSessionService.class);
        when(sessionService.openTime()).thenReturn(LocalTime.of(6, 0));
        when(sessionService.closeTime()).thenReturn(LocalTime.of(18, 0));
        service = new TradingSessionFenceService(jdbcTemplate, sessionService, new SimpleMeterRegistry(), 30);
        createSchema();
        seedOpenRegularSession();
    }

    @Test
    void acquireOpenSession_openFenceAtRegularTime_returnsSingleGateApproval() {
        TradingSessionFenceService.TradingSessionApproval approval = transactionTemplate.execute(status ->
                service.acquireOpenSession("005930", MarketType.VIRTUAL_PRICE)
        );

        assertThat(approval)
                .extracting(
                        TradingSessionFenceService.TradingSessionApproval::businessDate,
                        TradingSessionFenceService.TradingSessionApproval::sessionEpoch,
                        value -> value.businessEffectiveAt().toLocalTime()
                )
                .containsExactly(LocalDate.of(2026, 7, 1), 7L, LocalTime.NOON);
    }

    @Test
    void acquireOpenSession_clockAtClose_rejectsEvenWhenConfigAndFenceRemainOpen() {
        jdbcTemplate.update(
                "update stock_simulation_clock set accumulated_real_seconds = 5400 where clock_id = 'DEFAULT'"
        );

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                service.acquireOpenSession("005930", MarketType.VIRTUAL_PRICE)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Market session is closed");
    }

    @Test
    void acquireOpenSession_businessDateMismatch_failsClosed() {
        jdbcTemplate.update(
                "update stock_market_business_state set active_business_date = date '2026-07-02' where state_id = 'DEFAULT'"
        );

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                service.acquireOpenSession("005930", MarketType.VIRTUAL_PRICE)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Market session is closed");
    }

    @Test
    void acquireOpenSession_preparingBusinessDateStillSet_failsClosed() {
        jdbcTemplate.update(
                "update stock_market_business_state set preparing_business_date = date '2026-07-02' where state_id = 'DEFAULT'"
        );

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                service.acquireOpenSession("005930", MarketType.VIRTUAL_PRICE)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Market session is closed");
    }

    @Test
    void acquireOwnedOpenOrderEntrySession_ownedPendingOrder_returnsDescriptorAndFenceApproval() {
        TradingSessionFenceService.OwnedOrderSessionApproval approval = transactionTemplate.execute(status ->
                service.acquireOwnedOpenOrderEntrySession("owner-user", 11L)
        );

        assertThat(approval)
                .extracting(
                        TradingSessionFenceService.OwnedOrderSessionApproval::symbol,
                        TradingSessionFenceService.OwnedOrderSessionApproval::marketType,
                        TradingSessionFenceService.OwnedOrderSessionApproval::orderSide,
                        TradingSessionFenceService.OwnedOrderSessionApproval::businessDate,
                        TradingSessionFenceService.OwnedOrderSessionApproval::sessionEpoch
                )
                .containsExactly(
                        "005930",
                        MarketType.VIRTUAL_PRICE,
                        OrderSide.BUY,
                        LocalDate.of(2026, 7, 1),
                        7L
                );
    }

    @Test
    void acquireOwnedOpenOrderEntrySession_foreignOwner_doesNotRevealOrder() {
        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                service.acquireOwnedOpenOrderEntrySession("foreign-user", 11L)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    void acquireOwnedOpenOrderEntrySession_closeTime_rejectsAmendWithoutSecondDescriptorQuery() {
        jdbcTemplate.update(
                "update stock_simulation_clock set accumulated_real_seconds = 5400 where clock_id = 'DEFAULT'"
        );

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                service.acquireOwnedOpenOrderEntrySession("owner-user", 11L)
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Market session is closed");
    }

    @Test
    void acquireOwnedOpenOrderMutationSession_closeFenceCommitted_rejectsLateCancel() {
        jdbcTemplate.update(
                """
                update stock_market_session_fence
                   set session_epoch = session_epoch + 1,
                       session_state = 'CLOSED'
                 where market_type = 'VIRTUAL_PRICE'
                   and symbol = '005930'
                """
        );

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                service.acquireOwnedOpenOrderMutationSession(
                        "owner-user",
                        11L,
                        "Only pending orders can be cancelled"
                )
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Market ledger is closed");
    }

    private DataSource createDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:trading_session_fence;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void createSchema() {
        jdbcTemplate.execute("drop all objects");
        jdbcTemplate.execute(
                """
                create table stock_virtual_market_config (
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_order_book_market_config (
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null
                )
                """
        );
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
                create table stock_simulation_clock (
                    clock_id varchar(20) primary key,
                    base_simulation_date date not null,
                    real_seconds_per_simulation_day integer not null,
                    accumulated_real_seconds bigint not null,
                    running boolean not null,
                    last_started_at timestamp,
                    last_heartbeat_at timestamp
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
                    primary key (market_type, symbol)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_account (
                    id bigint primary key,
                    user_key varchar(64),
                    status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_order (
                    id bigint primary key,
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    market_type varchar(30) not null,
                    side varchar(10) not null,
                    status varchar(20) not null
                )
                """
        );
    }

    private void seedOpenRegularSession() {
        jdbcTemplate.update(
                "insert into stock_virtual_market_config(symbol, enabled, market_status) values ('005930', true, 'OPEN')"
        );
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date, raw_simulation_date
                ) values ('DEFAULT', date '2026-07-01', null, date '2026-07-01')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at, last_heartbeat_at
                )
                values ('DEFAULT', date '2026-07-01', 7200, 3600, false, null, null)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_epoch, session_state
                )
                values ('VIRTUAL_PRICE', '005930', date '2026-07-01', 7, 'OPEN')
                """
        );
        jdbcTemplate.update(
                "insert into stock_account(id, user_key, status) values (1, 'owner-user', 'ACTIVE')"
        );
        jdbcTemplate.update(
                """
                insert into stock_order(id, account_id, symbol, market_type, side, status)
                values (11, 1, '005930', 'VIRTUAL_PRICE', 'BUY', 'PENDING')
                """
        );
    }
}
