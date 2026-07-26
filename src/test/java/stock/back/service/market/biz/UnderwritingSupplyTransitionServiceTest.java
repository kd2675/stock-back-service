package stock.back.service.market.biz;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.UnderwritingSupplyActivationRequest;
import stock.back.service.market.vo.UnderwritingSupplySuspensionRequest;
import web.common.core.simulation.SimulationClockSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnderwritingSupplyTransitionServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime PRE_OPEN = BUSINESS_DATE.atTime(5, 0);

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private SimulationClockService simulationClockService;
    private MarketLedgerFreezeGuard freezeGuard;
    private UnderwritingSupplyTransitionService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:underwriting_transition_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(
                new FileSystemResource(batchH2Ddl())
        ).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentSnapshot()).thenReturn(
                clockSnapshot(false, PRE_OPEN)
        );
        SimulationMarketSessionService marketSessionService =
                new SimulationMarketSessionService(
                        simulationClockService,
                        "06:00",
                        "18:00"
                );
        freezeGuard = mock(MarketLedgerFreezeGuard.class);
        when(freezeGuard.acquireJdbcPreOpenMutationPermit(
                "issue-underwriter scaled supply activation"
        )).thenReturn(BUSINESS_DATE);
        when(freezeGuard.acquireJdbcMutationPermit(
                "issue-underwriter supply emergency suspension"
        )).thenReturn(BUSINESS_DATE);
        service = new UnderwritingSupplyTransitionService(
                jdbcTemplate,
                new ObjectMapper(),
                simulationClockService,
                marketSessionService,
                freezeGuard,
                new MarketRoleOrderCleanupService(jdbcTemplate)
        );
        seedContract();
    }

    @Test
    void activate_defaultScaledPolicy_setsFiniteQuotaWithoutCreatingOrders() {
        transactionTemplate.executeWithoutResult(status ->
                service.activate(401L, null, "stock-admin")
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select status, stabilization_start_date,
                       stabilization_end_date,
                       stabilization_quantity_limit,
                       stabilization_amount_limit, policy_version
                  from stock_underwriting_contract
                 where id = 401
                """
        )).containsEntry("status", "STABILIZING")
                .containsEntry(
                        "stabilization_start_date",
                        java.sql.Date.valueOf(BUSINESS_DATE)
                )
                .containsEntry(
                        "stabilization_end_date",
                        java.sql.Date.valueOf(BUSINESS_DATE.plusDays(19))
                )
                .containsEntry("stabilization_quantity_limit", 5_000L)
                .containsEntry(
                        "stabilization_amount_limit",
                        new BigDecimal("5000000.00")
                )
                .containsEntry("policy_version", 2L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'UNDERWRITING_CONTRACT'
                   and scope_key = 'INITIAL-ISSUE:DEMO001'
                   and version_no = 2
                """,
                Integer.class
        )).isOne();
    }

    @Test
    void activate_outsideScaledRate_rejectsBeforeMutation() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.activate(
                        401L,
                        new UnderwritingSupplyActivationRequest(
                                new BigDecimal("0.30"),
                                20,
                                "too large"
                        ),
                        "stock-admin"
                )
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("between 0.01 and 0.25");

        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_underwriting_contract where id = 401",
                String.class
        )).isEqualTo("ALLOCATED");
    }

    @Test
    void activate_missingTradableShares_rejectsBeforeMutation() {
        jdbcTemplate.update(
                "update stock_order_book_instrument set tradable_shares = 0 where symbol = 'DEMO001'"
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.activate(401L, null, "stock-admin")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("Activate the order-book and automatic market");

        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_underwriting_contract where id = 401",
                String.class
        )).isEqualTo("ALLOCATED");
    }

    @Test
    void activate_afterStockSplit_usesCurrentInventoryUnitsAndAdjustedIssuePrice() {
        jdbcTemplate.update(
                """
                update stock_order_book_instrument
                   set issued_shares = 500000,
                       tradable_shares = 250000
                 where symbol = 'DEMO001'
                """
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = quantity * 5,
                       average_price = average_price / 5
                 where symbol = 'DEMO001'
                """
        );
        jdbcTemplate.update(
                """
                update stock_underwriting_contract
                   set issue_price = issue_price / 5
                 where id = 401
                """
        );

        transactionTemplate.executeWithoutResult(status ->
                service.activate(401L, null, "stock-admin")
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select stabilization_quantity_limit, stabilization_amount_limit
                  from stock_underwriting_contract
                 where id = 401
                """
        )).containsEntry("stabilization_quantity_limit", 25_000L)
                .containsEntry(
                        "stabilization_amount_limit",
                        new BigDecimal("5000000.00")
                );
    }

    @Test
    void activate_issuedShareHoldingMismatch_rejectsBeforeMutation() {
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = quantity - 1
                 where account_id = 102
                   and symbol = 'DEMO001'
                """
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.activate(401L, null, "stock-admin")
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("supply reconciliation failed");

        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_underwriting_contract where id = 401",
                String.class
        )).isEqualTo("ALLOCATED");
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'UNDERWRITING_CONTRACT'
                """,
                Integer.class
        )).isOne();
    }

    @Test
    void suspend_activeSupply_cancelsDedicatedOrdersAndKeepsSpentBudget() {
        transactionTemplate.executeWithoutResult(status ->
                service.activate(401L, null, "stock-admin")
        );
        seedOpenSupplyOrderAndBudget();

        transactionTemplate.executeWithoutResult(status ->
                service.suspend(
                        401L,
                        new UnderwritingSupplySuspensionRequest("emergency stop"),
                        "stock-admin"
                )
        );

        assertThat(jdbcTemplate.queryForMap(
                "select status, reserved_cash from stock_order where id = 701"
        )).containsEntry("status", "CANCELLED")
                .containsEntry("reserved_cash", BigDecimal.ZERO.setScale(2));
        assertThat(jdbcTemplate.queryForObject(
                """
                select reserved_quantity
                  from stock_holding
                 where account_id = 101
                   and symbol = 'DEMO001'
                """,
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_underwriting_contract where id = 401",
                String.class
        )).isEqualTo("ALLOCATED");
        assertThat(jdbcTemplate.queryForMap(
                """
                select submitted_quantity, submitted_amount,
                       cancelled_order_count, state_status, gate_reason
                  from stock_underwriting_daily_supply_state
                 where simulation_trade_date = ?
                   and underwriting_contract_id = 401
                """,
                BUSINESS_DATE
        )).containsEntry("submitted_quantity", 100L)
                .containsEntry("submitted_amount", new BigDecimal("100000.00"))
                .containsEntry("cancelled_order_count", 1L)
                .containsEntry("state_status", "SUSPENDED")
                .containsEntry("gate_reason", "ADMIN_SUSPENDED");
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'UNDERWRITING_CONTRACT'
                   and scope_key = 'INITIAL-ISSUE:DEMO001'
                   and status = 'ACTIVE'
                """,
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'UNDERWRITING_CONTRACT'
                   and scope_key = 'INITIAL-ISSUE:DEMO001'
                   and status = 'RETIRED'
                """,
                Integer.class
        )).isEqualTo(2);
        verify(freezeGuard).acquireJdbcMutationPermit(
                "issue-underwriter supply emergency suspension"
        );
    }

    @Test
    void suspend_eodFreezeInProgress_leavesContractOrderAndReservationUnchanged() {
        transactionTemplate.executeWithoutResult(status ->
                service.activate(401L, null, "stock-admin")
        );
        seedOpenSupplyOrderAndBudget();
        when(freezeGuard.acquireJdbcMutationPermit(
                "issue-underwriter supply emergency suspension"
        )).thenThrow(StockException.conflict("ledger freeze is in progress"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                service.suspend(401L, null, "stock-admin")
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("ledger freeze is in progress");

        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_underwriting_contract where id = 401",
                String.class
        )).isEqualTo("STABILIZING");
        assertThat(jdbcTemplate.queryForMap(
                "select status, reserved_cash from stock_order where id = 701"
        )).containsEntry("status", "PENDING")
                .containsEntry("reserved_cash", BigDecimal.ZERO.setScale(2));
        assertThat(jdbcTemplate.queryForObject(
                """
                select reserved_quantity
                  from stock_holding
                 where account_id = 101
                   and symbol = 'DEMO001'
                """,
                Long.class
        )).isEqualTo(100L);
    }

    @Test
    void activate_afterSuspension_addsNewTrancheToPersistedUsage() {
        transactionTemplate.executeWithoutResult(status ->
                service.activate(401L, null, "stock-admin")
        );
        seedOpenSupplyOrderAndBudget();
        transactionTemplate.executeWithoutResult(status ->
                service.suspend(401L, null, "stock-admin")
        );
        transactionTemplate.executeWithoutResult(status ->
                service.activate(401L, null, "stock-admin")
        );

        assertThat(jdbcTemplate.queryForMap(
                """
                select stabilization_quantity_limit, stabilization_amount_limit
                  from stock_underwriting_contract
                 where id = 401
                """
        )).containsEntry("stabilization_quantity_limit", 5_100L)
                .containsEntry(
                        "stabilization_amount_limit",
                        new BigDecimal("5100000.00")
                );
    }

    private void seedContract() {
        long participantId = jdbcTemplate.queryForObject(
                """
                select id
                  from stock_market_participant
                 where participant_code = 'DEFAULT_ISSUE_UNDERWRITER'
                """,
                Long.class
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares,
                    tradable_shares, tick_size, price_limit_rate,
                    enabled, created_at, updated_at
                ) values (
                    'DEMO001', '인수 공급 테스트', 'KOSPI', 1000,
                    100000, 50000, 1, 30, true, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(
                    symbol, enabled, market_status, updated_at
                ) values ('DEMO001', true, 'CLOSED', ?)
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_price(
                    symbol, current_price, previous_close, price_time, provider
                ) values ('DEMO001', 1000, 1000, ?, 'test')
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_market_config(
                    symbol, enabled, max_order_quantity,
                    order_ttl_seconds, updated_at
                ) values ('DEMO001', true, 4, 15, ?)
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    101, 'stock-issue-underwriter-demo001', 'UW-DEMO001',
                    'ACTIVE', 'ISSUE_UNDERWRITER',
                    'ISSUE_UNDERWRITER:DEFAULT', 0, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_market_participant_account(
                    participant_id, account_id, account_role, desk_code,
                    effective_from, status, created_at, updated_at
                ) values (
                    ?, 101, 'ISSUE_UNDERWRITER', 'DEMO001',
                    ?, 'ACTIVE', ?, ?
                )
                """,
                participantId,
                BUSINESS_DATE.minusDays(1),
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (101, 'DEMO001', 50000, 0, 1000, ?)
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    102, 'stock-issuance-lockup-demo001', 'LOCK-DEMO001',
                    'ACTIVE', 'SYSTEM_CUSTODY',
                    'SYSTEM_CUSTODY:DEFAULT', 0, ?, ?
                )
                """,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (102, 'DEMO001', 50000, 0, 1000, ?)
                """,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_contract(
                    id, contract_code, symbol, participant_id, account_id,
                    total_issue_quantity, tradable_allocation_quantity,
                    locked_allocation_quantity, external_allocation_quantity,
                    underwritten_quantity, issue_price, underwriting_type,
                    stabilization_quantity_limit, stabilization_amount_limit,
                    status, policy_version, created_at, updated_at
                ) values (
                    401, 'INITIAL-ISSUE:DEMO001', 'DEMO001', ?, 101,
                    100000, 50000, 50000, 0, 50000, 1000,
                    'FIRM_COMMITMENT', 0, 0, 'ALLOCATED', 1, ?, ?
                )
                """,
                participantId,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no,
                    effective_business_date, status, config_json,
                    change_reason, changed_by, created_at, updated_at
                ) values (
                    'UNDERWRITING_CONTRACT', 'INITIAL-ISSUE:DEMO001', 1,
                    ?, 'ACTIVE', '{}',
                    'initial inactive policy', 'test', ?, ?
                )
                """,
                BUSINESS_DATE,
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_security_allocation_ledger(
                    idempotency_key, event_type, underwriting_contract_id,
                    source_account_id, destination_account_id, symbol,
                    quantity, unit_price, allocation_reason,
                    tradability_status, effective_business_date, created_at
                ) values
                    (
                        'INITIAL-ISSUE:DEMO001:UNDERWRITER',
                        'INITIAL_ISSUE', 401, null, 101, 'DEMO001',
                        50000, 1000, 'INITIAL_FLOAT_UNDERWRITER',
                        'TRADABLE', ?, ?
                    ),
                    (
                        'INITIAL-ISSUE:DEMO001:LOCKED',
                        'INITIAL_ISSUE', 401, null, 102, 'DEMO001',
                        50000, 1000, 'INITIAL_LOCKED_CUSTODY',
                        'LOCKED', ?, ?
                    )
                """,
                BUSINESS_DATE,
                PRE_OPEN,
                BUSINESS_DATE,
                PRE_OPEN
        );
    }

    private void seedOpenSupplyOrderAndBudget() {
        long participantId = jdbcTemplate.queryForObject(
                "select participant_id from stock_underwriting_contract where id = 401",
                Long.class
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = 100
                 where account_id = 101
                   and symbol = 'DEMO001'
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, client_order_id, account_id, origin_type,
                    self_trade_group_id, symbol, market_type, side,
                    order_type, status, limit_price, quantity,
                    filled_quantity, reserved_cash, expires_at,
                    created_at, updated_at
                ) values (
                    701, 'uw-open', 101, 'ISSUE_UNDERWRITER',
                    'ISSUE_UNDERWRITER:DEFAULT', 'DEMO001',
                    'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING',
                    1000, 100, 0, 0, ?, ?, ?
                )
                """,
                PRE_OPEN.plusMinutes(10),
                PRE_OPEN,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_order_strategy_origin(
                    order_id, origin_type, participant_id,
                    underwriting_contract_id, policy_version, created_at
                ) values (701, 'ISSUE_UNDERWRITER', ?, 401, 2, ?)
                """,
                participantId,
                PRE_OPEN
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_daily_supply_state(
                    simulation_trade_date, underwriting_contract_id,
                    reference_daily_volume, submission_quantity_limit,
                    submission_amount_limit, submitted_quantity, submitted_amount,
                    generated_order_count, cancelled_order_count,
                    last_order_price, state_status, gate_reason,
                    policy_version, version, created_at, updated_at
                ) values (
                    ?, 401, 1500, 150, 150000, 100, 100000,
                    1, 0, 1000, 'ACTIVE', 'WITHIN_LIMITS',
                    2, 1, ?, ?
                )
                """,
                BUSINESS_DATE,
                PRE_OPEN,
                PRE_OPEN
        );
    }

    private SimulationClockSnapshot clockSnapshot(
            boolean running,
            LocalDateTime simulationDateTime
    ) {
        return new SimulationClockSnapshot(
                BUSINESS_DATE,
                simulationDateTime,
                BUSINESS_DATE.atStartOfDay(),
                simulationDateTime,
                BUSINESS_DATE.atStartOfDay(),
                7_200,
                running,
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
