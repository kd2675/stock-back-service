package stock.back.service.common.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the business tables that are intentionally not JPA entities in create-drop tests.
 * Production startup never uses this component; synchronized MySQL DDL and schema readiness own
 * the real schema.
 */
@Component
@Profile("test")
@Order(Ordered.HIGHEST_PRECEDENCE)
class StockTestEodSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    StockTestEodSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute(
                """
                create table if not exists stock_market_business_state (
                    state_id varchar(20) primary key,
                    active_business_date date not null,
                    preparing_business_date date,
                    raw_simulation_date date not null,
                    version bigint not null default 0,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table if not exists stock_market_session_fence (
                    market_type varchar(20) not null,
                    symbol varchar(20) not null,
                    business_date date not null,
                    session_epoch bigint not null,
                    session_state varchar(20) not null,
                    state_changed_at timestamp not null,
                    version bigint not null default 0,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    primary key (market_type, symbol)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create index if not exists idx_stock_market_session_fence_state
                    on stock_market_session_fence(business_date, session_state, market_type, symbol)
                """
        );
        jdbcTemplate.execute(
                """
                create table if not exists stock_execution_account_day_summary (
                    simulation_trade_date date not null,
                    account_id bigint not null,
                    execution_count bigint not null default 0,
                    buy_quantity bigint not null default 0,
                    sell_quantity bigint not null default 0,
                    gross_amount decimal(19, 2) not null default 0,
                    buy_gross_amount decimal(19, 2) not null default 0,
                    sell_gross_amount decimal(19, 2) not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    fee_amount decimal(19, 2) not null default 0,
                    tax_amount decimal(19, 2) not null default 0,
                    realized_profit decimal(19, 2) not null default 0,
                    last_executed_at timestamp null,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create index if not exists idx_stock_execution_account_day_account_date
                    on stock_execution_account_day_summary(account_id, simulation_trade_date)
                """
        );
        jdbcTemplate.execute(
                """
                create table if not exists stock_post_close_cycle (
                    id bigint auto_increment primary key,
                    business_date date not null,
                    scope_type varchar(20) not null,
                    scope_key varchar(40) not null,
                    cycle_kind varchar(20) not null default 'TRADING',
                    skip_reason varchar(500),
                    phase varchar(60) not null default 'OPEN',
                    status varchar(20) not null default 'PENDING',
                    phase_revision int not null default 1,
                    version bigint not null default 0,
                    owner_id varchar(128),
                    lease_until timestamp,
                    next_retry_at timestamp,
                    close_run_id bigint,
                    settlement_eligible_at timestamp,
                    attempt_count int not null default 0,
                    started_at timestamp,
                    completed_at timestamp,
                    last_error_code varchar(80),
                    last_error_message varchar(1000),
                    build_version varchar(100),
                    schema_version varchar(100),
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    constraint uk_stock_post_close_cycle_scope
                        unique (business_date, scope_type, scope_key)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create index if not exists idx_stock_post_close_cycle_scope_date_status
                    on stock_post_close_cycle(scope_type, scope_key, business_date, status, id)
                """
        );
        jdbcTemplate.execute(
                """
                create table if not exists stock_market_close_run (
                    id bigint auto_increment primary key,
                    symbol varchar(20),
                    business_date date not null,
                    closed_at timestamp,
                    status varchar(20) not null,
                    cancelled_order_count int not null default 0,
                    holding_snapshot_count int not null default 0,
                    price_rollover_count int not null default 0,
                    created_at timestamp,
                    completed_at timestamp
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table if not exists stock_execution_daily_account_snapshot (
                    id bigint auto_increment primary key,
                    close_run_id bigint not null,
                    symbol varchar(20) not null,
                    simulation_trade_date date not null,
                    account_id bigint not null,
                    participant_category varchar(30) not null,
                    execution_count bigint not null default 0,
                    buy_quantity bigint not null default 0,
                    sell_quantity bigint not null default 0,
                    buy_amount decimal(19, 2) not null default 0,
                    sell_amount decimal(19, 2) not null default 0,
                    net_cash_flow decimal(19, 2) not null default 0,
                    execution_amount decimal(19, 2) not null default 0,
                    last_executed_at timestamp,
                    created_at timestamp not null,
                    unique (close_run_id, symbol, account_id)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create index if not exists idx_stock_execution_daily_account_account_date
                    on stock_execution_daily_account_snapshot(account_id, simulation_trade_date, close_run_id)
                """
        );
    }
}
