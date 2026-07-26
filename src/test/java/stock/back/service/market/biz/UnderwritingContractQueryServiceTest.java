package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import stock.back.service.market.vo.UnderwritingContractResponse;

import static org.assertj.core.api.Assertions.assertThat;

class UnderwritingContractQueryServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = BUSINESS_DATE.atTime(9, 0);

    private JdbcTemplate jdbcTemplate;
    private UnderwritingContractQueryService service;
    private long underwriterParticipantId;
    private long custodyAccountId;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:underwriting_query_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new FileSystemResource(batchH2Ddl())).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        service = new UnderwritingContractQueryService(JdbcClient.create(dataSource));
        underwriterParticipantId = jdbcTemplate.queryForObject(
                "select id from stock_market_participant "
                        + "where participant_code = 'DEFAULT_ISSUE_UNDERWRITER'",
                Long.class
        );
        custodyAccountId = jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = 'stock-system-custody'",
                Long.class
        );
        seedRoleSeparatedIssue();
    }

    @Test
    void getContracts_roleSeparatedIssue_exposesBalancedContractAccountAndAllocationLedger() {
        List<UnderwritingContractResponse> result = service.getContracts();

        assertThat(result).singleElement().satisfies(contract -> {
            assertThat(contract.contractCode()).isEqualTo("INITIAL-ISSUE:DEMO001");
            assertThat(contract.tradableShareRate()).isEqualByComparingTo("0.500000");
            assertThat(contract.account().participantType()).isEqualTo("ISSUE_UNDERWRITER");
            assertThat(contract.account().participantSelfTradeGroupId())
                    .isEqualTo(contract.account().accountSelfTradeGroupId());
            assertThat(contract.account().holdingQuantity()).isEqualTo(50_000L);
            assertThat(contract.account().holdingMarketValue())
                    .isEqualByComparingTo("60000000.00");
            assertThat(contract.reconciliation().contractQuantityBalanced()).isTrue();
            assertThat(contract.reconciliation().instrumentQuantityCovered()).isTrue();
            assertThat(contract.reconciliation().allocationLedgerMatched()).isTrue();
            assertThat(contract.reconciliation().currentTotalHoldingQuantity())
                    .isEqualTo(100_000L);
            assertThat(contract.reconciliation().holdingSupplyMatched()).isTrue();
            assertThat(contract.reconciliation().roleEligible()).isTrue();
            assertThat(contract.reconciliation().issues()).isEmpty();
            assertThat(contract.allocations()).hasSize(2);
            assertThat(contract.allocations())
                    .extracting(allocation -> allocation.tradabilityStatus())
                    .containsExactly("TRADABLE", "LOCKED");
        });
    }

    @Test
    void getContracts_dedicatedAccountHasUnownedOrderAndOtherSymbol_marksRoleIneligible() {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash,
                    created_at, updated_at
                ) values (
                    'rogue-underwriter-order', 101, 'MANUAL_PARTICIPANT',
                    'ISSUE_UNDERWRITER:DEFAULT', 'DEMO001', 'ORDER_BOOK',
                    'BUY', 'LIMIT', 'PENDING', 1100, 10, 0, 11000, ?, ?
                )
                """,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (101, 'DEMO999', 10, 0, 500, ?)
                """,
                NOW
        );

        UnderwritingContractResponse contract = service.getContracts().getFirst();

        assertThat(contract.reconciliation().roleEligible()).isFalse();
        assertThat(contract.reconciliation().issues()).contains(
                "ROLE_NON_CONTRACT_OPEN_ORDER",
                "ROLE_UNMANAGED_HOLDING"
        );
        assertThat(contract.account().nonContractOpenOrderCount()).isEqualTo(1L);
        assertThat(contract.account().unmanagedHoldingCount()).isEqualTo(1L);
    }

    @Test
    void getContracts_currentHoldingSupplyMismatch_isReportedSeparatelyFromImmutableLedger() {
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = quantity - 1
                 where account_id = ?
                   and symbol = 'DEMO001'
                """,
                custodyAccountId
        );

        UnderwritingContractResponse contract = service.getContracts().getFirst();

        assertThat(contract.reconciliation().allocationLedgerMatched()).isTrue();
        assertThat(contract.reconciliation().currentTotalHoldingQuantity())
                .isEqualTo(99_999L);
        assertThat(contract.reconciliation().holdingSupplyMatched()).isFalse();
        assertThat(contract.reconciliation().issues())
                .contains("HOLDING_SUPPLY_MISMATCH");
    }

    @Test
    void getContracts_laterCapitalGrowth_doesNotInvalidateInitialContractLedger() {
        jdbcTemplate.update(
                """
                update stock_order_book_instrument
                   set issued_shares = 110000,
                       tradable_shares = 60000
                 where symbol = 'DEMO001'
                """
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = quantity + 10000
                 where account_id = ?
                   and symbol = 'DEMO001'
                """,
                custodyAccountId
        );

        UnderwritingContractResponse contract = service.getContracts().getFirst();

        assertThat(contract.reconciliation().instrumentQuantityCovered()).isTrue();
        assertThat(contract.reconciliation().allocationLedgerMatched()).isTrue();
        assertThat(contract.reconciliation().holdingSupplyMatched()).isTrue();
        assertThat(contract.reconciliation().issues())
                .doesNotContain("INSTRUMENT_QUANTITY_UNDERFLOW");
    }

    @Test
    void getContracts_completedContractWithClosedHistoricalAccount_keepsRoleEligible() {
        jdbcTemplate.update(
                """
                update stock_underwriting_contract
                   set status = 'COMPLETED',
                       updated_at = ?
                 where id = 401
                """,
                NOW.plusDays(1)
        );
        jdbcTemplate.update(
                """
                update stock_account
                   set status = 'CLOSED',
                       updated_at = ?
                 where id = 101
                """,
                NOW.plusDays(1)
        );
        jdbcTemplate.update(
                """
                update stock_market_participant_account
                   set status = 'CLOSED',
                       effective_to = ?,
                       updated_at = ?
                 where account_id = 101
                """,
                BUSINESS_DATE.plusDays(1),
                NOW.plusDays(1)
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = 0,
                       updated_at = ?
                 where account_id = 101
                   and symbol = 'DEMO001'
                """,
                NOW.plusDays(1)
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = quantity + 50000,
                       updated_at = ?
                 where account_id = ?
                   and symbol = 'DEMO001'
                """,
                NOW.plusDays(1),
                custodyAccountId
        );

        UnderwritingContractResponse contract = service.getContracts().getFirst();

        assertThat(contract.reconciliation()).satisfies(reconciliation -> {
            assertThat(reconciliation.roleEligible()).isTrue();
            assertThat(reconciliation.holdingSupplyMatched()).isTrue();
            assertThat(reconciliation.issues()).isEmpty();
        });
    }

    @Test
    void getContracts_openContractWithClosedAccount_remainsRoleIneligible() {
        jdbcTemplate.update(
                "update stock_account set status = 'CLOSED' where id = 101"
        );
        jdbcTemplate.update(
                """
                update stock_market_participant_account
                   set status = 'CLOSED',
                       effective_to = ?
                 where account_id = 101
                """,
                BUSINESS_DATE
        );

        UnderwritingContractResponse contract = service.getContracts().getFirst();

        assertThat(contract.reconciliation().issues()).contains(
                "ROLE_ACCOUNT_NOT_ACTIVE",
                "ROLE_MAPPING_MISMATCH"
        );
    }

    @Test
    void getContract_supplyState_exposesNonRefundableBudgetAndExecutionSeparately() {
        jdbcTemplate.update(
                """
                update stock_underwriting_contract
                   set stabilization_start_date = ?,
                       stabilization_end_date = ?,
                       stabilization_quantity_limit = 5000,
                       stabilization_amount_limit = 5000000,
                       status = 'STABILIZING',
                       policy_version = 2
                 where id = 401
                """,
                BUSINESS_DATE,
                BUSINESS_DATE.plusDays(19)
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_daily_supply_state(
                    simulation_trade_date, underwriting_contract_id,
                    reference_daily_volume, submission_quantity_limit,
                    submission_amount_limit, submitted_quantity, submitted_amount,
                    generated_order_count, cancelled_order_count, last_order_price,
                    state_status, gate_reason, policy_version, version,
                    created_at, updated_at
                ) values (
                    ?, 401, 1500, 150, 180000,
                    120, 144000, 2, 1, 1200,
                    'ACTIVE', 'WITHIN_LIMITS', 2, 3, ?, ?
                )
                """,
                BUSINESS_DATE,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, client_order_id, account_id, origin_type,
                    self_trade_group_id, symbol, market_type, side,
                    order_type, status, limit_price, quantity,
                    filled_quantity, average_fill_price, reserved_cash,
                    created_at, updated_at
                ) values (
                    701, 'underwriting-filled', 101, 'ISSUE_UNDERWRITER',
                    'ISSUE_UNDERWRITER:DEFAULT', 'DEMO001', 'ORDER_BOOK', 'SELL',
                    'LIMIT', 'FILLED', 1200, 100, 100, 1200, 0, ?, ?
                )
                """,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_order_strategy_origin(
                    order_id, origin_type, participant_id,
                    underwriting_contract_id, policy_version, created_at
                ) values (701, 'ISSUE_UNDERWRITER', ?, 401, 2, ?)
                """,
                underwriterParticipantId,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    order_id, account_id, symbol, side, quantity, price,
                    gross_amount, fee_amount, tax_amount, net_amount,
                    source, executed_at
                ) values (
                    701, 101, 'DEMO001', 'SELL', 100, 1200,
                    120000, 0, 0, 120000, 'INTERNAL_ORDER_BOOK', ?
                )
                """,
                NOW
        );

        UnderwritingContractResponse contract = service.getContract(401L);

        assertThat(contract.supply().configuredSupplyRate())
                .isEqualByComparingTo("0.100000");
        assertThat(contract.supply().lifetimeSubmittedQuantity()).isEqualTo(120L);
        assertThat(contract.supply().lifetimeExecutedQuantity()).isEqualTo(100L);
        assertThat(contract.supply().remainingSubmissionQuantity()).isEqualTo(4_880L);
        assertThat(contract.supply().remainingSubmissionAmount())
                .isEqualByComparingTo("4856000.00");
        assertThat(contract.supply().latestDailyState()).satisfies(state -> {
            assertThat(state.referenceDailyVolume()).isEqualTo(1_500L);
            assertThat(state.submissionQuantityLimit()).isEqualTo(150L);
            assertThat(state.submittedQuantity()).isEqualTo(120L);
            assertThat(state.gateReason()).isEqualTo("WITHIN_LIMITS");
        });
    }

    private void seedRoleSeparatedIssue() {
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares,
                    tradable_shares, tick_size, price_limit_rate,
                    enabled, created_at, updated_at
                ) values (
                    'DEMO001', '역할 분리 종목', 'ORDERBOOK', 1000, 100000,
                    50000, 1, 30, true, ?, ?
                )
                """,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_price(
                    symbol, current_price, previous_close, price_time, provider
                ) values ('DEMO001', 1200, 1000, ?, 'test')
                """,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                    id, symbol, action_type, share_quantity, issue_price,
                    status, listed_at, description, created_at
                ) values (
                    301, 'DEMO001', 'INITIAL_ISSUE', 100000, 1000,
                    'LISTED', ?, 'role-separated test issue', ?
                )
                """,
                NOW,
                NOW
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
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_market_participant_account(
                    participant_id, account_id, account_role, desk_code,
                    effective_from, effective_to, status, created_at, updated_at
                ) values (
                    ?, 101, 'ISSUE_UNDERWRITER', 'DEMO001',
                    ?, null, 'ACTIVE', ?, ?
                )
                """,
                underwriterParticipantId,
                BUSINESS_DATE,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_contract(
                    id, contract_code, corporate_action_id, symbol,
                    participant_id, account_id, total_issue_quantity,
                    tradable_allocation_quantity, locked_allocation_quantity,
                    external_allocation_quantity, underwritten_quantity,
                    issue_price, underwriting_type,
                    stabilization_quantity_limit, stabilization_amount_limit,
                    status, policy_version, created_at, updated_at
                ) values (
                    401, 'INITIAL-ISSUE:DEMO001', 301, 'DEMO001',
                    ?, 101, 100000, 50000, 50000, 0, 50000,
                    1000, 'FIRM_COMMITMENT', 0, 0,
                    'ALLOCATED', 1, ?, ?
                )
                """,
                underwriterParticipantId,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (101, 'DEMO001', 50000, 0, 1000, ?)
                """,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (?, 'DEMO001', 50000, 0, 1000, ?)
                """,
                custodyAccountId,
                NOW
        );
        insertAllocation(
                "INITIAL-ISSUE:DEMO001:TRADABLE",
                101,
                50_000L,
                "INITIAL_FLOAT_UNDERWRITER",
                "TRADABLE"
        );
        insertAllocation(
                "INITIAL-ISSUE:DEMO001:LOCKED",
                custodyAccountId,
                50_000L,
                "INITIAL_LOCKED_CUSTODY",
                "LOCKED"
        );
    }

    private void insertAllocation(
            String idempotencyKey,
            long destinationAccountId,
            long quantity,
            String reason,
            String tradability
    ) {
        jdbcTemplate.update(
                """
                insert into stock_security_allocation_ledger(
                    idempotency_key, event_type, corporate_action_id,
                    underwriting_contract_id, source_account_id,
                    destination_account_id, symbol, quantity, unit_price,
                    allocation_reason, tradability_status,
                    effective_business_date, unlock_business_date, created_at
                ) values (
                    ?, 'INITIAL_ISSUE', 301, 401, null,
                    ?, 'DEMO001', ?, 1000, ?, ?, ?, null, ?
                )
                """,
                idempotencyKey,
                destinationAccountId,
                quantity,
                reason,
                tradability,
                BUSINESS_DATE,
                NOW
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
