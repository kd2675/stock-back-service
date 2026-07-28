package stock.back.service.market.biz;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.InitialIssueAllocationRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;
import stock.back.service.market.vo.UnderwritingContractCreateRequest;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderBookInstrumentRoleSeparatedIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2027, 1, 27, 5, 0);

    private JdbcTemplate jdbcTemplate;
    private StockOrderBookInstrumentRepository instrumentRepository;
    private StockCorporateActionRepository corporateActionRepository;
    private StockOrderBookMarketConfigRepository marketConfigRepository;
    private SimulationClockService simulationClockService;
    private OrderBookInstrumentCommandService service;
    private SystemCustodyQueryService systemCustodyQueryService;
    private UnderwritingContractQueryService underwritingContractQueryService;
    private UnderwritingContractProvisionService underwritingProvisionService;
    private UnderwritingContractRecommendationService underwritingRecommendationService;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:role_separated_issue_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new FileSystemResource(batchH2Ddl())).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);

        StockInstrumentRepository virtualInstrumentRepository =
                mock(StockInstrumentRepository.class);
        StockPriceRepository priceRepository = mock(StockPriceRepository.class);
        StockAutoMarketConfigRepository autoMarketConfigRepository =
                mock(StockAutoMarketConfigRepository.class);
        instrumentRepository = mock(StockOrderBookInstrumentRepository.class);
        marketConfigRepository = mock(StockOrderBookMarketConfigRepository.class);
        corporateActionRepository = mock(StockCorporateActionRepository.class);
        simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService marketSessionService =
                mock(SimulationMarketSessionService.class);
        MarketLedgerFreezeGuard freezeGuard = mock(MarketLedgerFreezeGuard.class);

        when(instrumentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(corporateActionRepository.save(any())).thenAnswer(invocation -> {
            StockCorporateAction action = invocation.getArgument(0);
            ReflectionTestUtils.setField(action, "id", 321L);
            return action;
        });
        when(priceRepository.findById(any())).thenReturn(Optional.empty());
        when(simulationClockService.currentMarketDateTime()).thenReturn(NOW);
        when(simulationClockService.currentSnapshot()).thenReturn(pausedClock());
        when(marketSessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);
        when(freezeGuard.acquireMutationPermit(any())).thenReturn(NOW.toLocalDate());
        when(freezeGuard.acquireJdbcPreOpenMutationPermit(any())).thenReturn(NOW.toLocalDate());
        when(freezeGuard.acquireJdbcMutationPermit(any())).thenReturn(NOW.toLocalDate());

        service = new OrderBookInstrumentCommandService(
                virtualInstrumentRepository,
                priceRepository,
                autoMarketConfigRepository,
                instrumentRepository,
                marketConfigRepository,
                corporateActionRepository,
                jdbcTemplate,
                simulationClockService,
                freezeGuard
        );
        underwritingProvisionService = new UnderwritingContractProvisionService(
                jdbcTemplate,
                simulationClockService,
                freezeGuard
        );
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        systemCustodyQueryService = new SystemCustodyQueryService(jdbcClient);
        underwritingContractQueryService = new UnderwritingContractQueryService(
                jdbcClient,
                new ObjectMapper()
        );
        underwritingRecommendationService =
                new UnderwritingContractRecommendationService(jdbcClient);
    }

    @Test
    void create_defaultIssueStagesFloatAndLockedSharesWithoutEconomicRoles() {
        when(simulationClockService.currentSnapshot()).thenReturn(runningClock());

        OrderBookInstrumentResponse response = service.createOrderBookInstrument(
                defaultRequest()
        );

        assertThat(response.issuedShares()).isEqualTo(100_000L);
        assertThat(response.tradableShares()).isEqualTo(50_000L);
        ArgumentCaptor<StockOrderBookMarketConfig> marketConfigCaptor =
                ArgumentCaptor.forClass(StockOrderBookMarketConfig.class);
        verify(marketConfigRepository).save(marketConfigCaptor.capture());
        assertThat(marketConfigCaptor.getValue().getEnabled()).isFalse();
        assertThat(marketConfigCaptor.getValue().getMarketStatus())
                .isEqualTo(MarketSessionStatus.CLOSED);
        assertThat(jdbcTemplate.queryForList(
                """
                select account.user_key, account.account_code,
                       account.participant_category, account.self_trade_group_id,
                       holding.quantity, holding.reserved_quantity
                 from stock_holding holding
                  join stock_account account on account.id = holding.account_id
                 where holding.symbol = 'NEW001'
                 order by account.user_key
                """
        )).containsExactly(
                Map.of(
                        "user_key", "stock-issuance-float-new001",
                        "account_code", "FLOAT-NEW001",
                        "participant_category", "SYSTEM_CUSTODY",
                        "self_trade_group_id", "SYSTEM_CUSTODY:DEFAULT",
                        "quantity", 50_000L,
                        "reserved_quantity", 0L
                ),
                Map.of(
                        "user_key", "stock-issuance-lockup-new001",
                        "account_code", "LOCKUP-NEW001",
                        "participant_category", "SYSTEM_CUSTODY",
                        "self_trade_group_id", "SYSTEM_CUSTODY:DEFAULT",
                        "quantity", 50_000L,
                        "reserved_quantity", 0L
                )
        );
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_holding holding
                  join stock_account account on account.id = holding.account_id
                 where holding.symbol = 'NEW001'
                   and account.user_key = 'stock-system-custody'
                """,
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForMap(
                """
                select mapping.account_role, mapping.desk_code
                  from stock_market_participant_account mapping
                  join stock_account account on account.id = mapping.account_id
                 where account.user_key = 'stock-issuance-float-new001'
                """
        )).containsEntry("account_role", "SYSTEM_CUSTODY")
                .containsEntry("desk_code", "ISSUANCE_FLOAT:NEW001");
        assertThat(jdbcTemplate.queryForMap(
                """
                select mapping.account_role, mapping.desk_code
                  from stock_market_participant_account mapping
                  join stock_account account on account.id = mapping.account_id
                 where account.user_key = 'stock-issuance-lockup-new001'
                """
        )).containsEntry("account_role", "SYSTEM_CUSTODY")
                .containsEntry("desk_code", "ISSUANCE_LOCKUP:NEW001");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_underwriting_contract where symbol = 'NEW001'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForList(
                """
                select allocation_reason, tradability_status, quantity,
                       destination_account_id, effective_business_date
                  from stock_security_allocation_ledger
                 where symbol = 'NEW001'
                 order by allocation_reason
                """
        )).hasSize(2)
                .extracting(
                        row -> row.get("allocation_reason"),
                        row -> row.get("tradability_status"),
                        row -> row.get("quantity")
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "INITIAL_FLOAT_CUSTODY",
                                "TRADABLE",
                                50_000L
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "INITIAL_LOCKED_CUSTODY",
                                "LOCKED",
                                50_000L
                        )
                );
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_market_policy_version
                 where policy_scope = 'UNDERWRITING_CONTRACT'
                """,
                Integer.class
        )).isZero();
    }

    @Test
    void create_underwritingContractMovesOnlyStagedFloatInventory() {
        service.createOrderBookInstrument(defaultRequest());
        persistPendingInstrumentRows();

        long contractId = underwritingProvisionService.createContract(
                "NEW001",
                new UnderwritingContractCreateRequest(
                        "FIRM_COMMITMENT",
                        "독립 인수계약 생성 검증"
                ),
                "admin"
        );

        assertThat(contractId).isPositive();
        assertThat(jdbcTemplate.queryForMap(
                """
                select total_issue_quantity, tradable_allocation_quantity,
                       locked_allocation_quantity, external_allocation_quantity,
                       underwritten_quantity, underwriting_type, status
                  from stock_underwriting_contract
                 where id = ?
                """,
                contractId
        )).containsEntry("total_issue_quantity", 100_000L)
                .containsEntry("tradable_allocation_quantity", 50_000L)
                .containsEntry("locked_allocation_quantity", 50_000L)
                .containsEntry("external_allocation_quantity", 0L)
                .containsEntry("underwritten_quantity", 50_000L)
                .containsEntry("underwriting_type", "FIRM_COMMITMENT")
                .containsEntry("status", "ALLOCATED");
        assertThat(jdbcTemplate.queryForList(
                """
                select account.user_key, holding.quantity
                  from stock_holding holding
                  join stock_account account on account.id = holding.account_id
                 where holding.symbol = 'NEW001'
                 order by account.user_key
                """
        )).extracting(
                row -> row.get("user_key"),
                row -> row.get("quantity")
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                        "stock-issuance-float-new001",
                        0L
                ),
                org.assertj.core.groups.Tuple.tuple(
                        "stock-issuance-lockup-new001",
                        50_000L
                ),
                org.assertj.core.groups.Tuple.tuple(
                        "stock-issue-underwriter-new001",
                        50_000L
                )
        );
        assertThat(jdbcTemplate.queryForList(
                """
                select event_type, allocation_reason, underwriting_contract_id,
                       source_account_id, destination_account_id, quantity
                  from stock_security_allocation_ledger
                 where symbol = 'NEW001'
                 order by id
                """
        )).hasSize(3);
        assertThat(jdbcTemplate.queryForObject(
                "select coalesce(sum(quantity), 0) from stock_holding where symbol = 'NEW001'",
                Long.class
        )).isEqualTo(100_000L);
        assertThat(underwritingContractQueryService.getContract(contractId)
                .reconciliation()
                .issues()).isEmpty();
    }

    @Test
    void getRecommendations_stagedIssueReportsIndependentAccountCountsAndExactQuantity() {
        service.createOrderBookInstrument(defaultRequest());
        persistPendingInstrumentRows();

        var underwriting = underwritingRecommendationService.getRecommendation();
        var custody = systemCustodyQueryService.getOverview();

        assertThat(underwriting.recommendedUnderwriterOrganizationCount()).isEqualTo(1);
        assertThat(underwriting.recommendedAccountCountPerSymbol()).isEqualTo(1);
        assertThat(underwriting.recommendedRemainingContractCount()).isEqualTo(1);
        assertThat(underwriting.symbols()).singleElement().satisfies(symbol -> {
            assertThat(symbol.symbol()).isEqualTo("NEW001");
            assertThat(symbol.floatCustodyAvailableQuantity()).isEqualTo(50_000L);
            assertThat(symbol.creationEligible()).isTrue();
        });
        assertThat(custody.roleSeparatedIssueSymbolCount()).isEqualTo(1);
        assertThat(custody.recommendedIssuanceCustodyAccountCount()).isEqualTo(2);
        assertThat(custody.currentIssuanceCustodyAccountCount()).isEqualTo(2);
    }

    @Test
    void getRecommendations_multipleContractsForSymbol_returnsOneSymbol() {
        service.createOrderBookInstrument(defaultRequest());
        persistPendingInstrumentRows();
        long initialContractId = underwritingProvisionService.createContract(
                "NEW001",
                new UnderwritingContractCreateRequest(
                        "FIRM_COMMITMENT",
                        "initial contract"
                ),
                "admin"
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_contract(
                    contract_code, corporate_action_id, symbol,
                    participant_id, account_id, total_issue_quantity,
                    tradable_allocation_quantity, locked_allocation_quantity,
                    external_allocation_quantity, underwritten_quantity,
                    issue_price, underwriting_type, status, policy_version,
                    created_at, updated_at
                )
                select 'FOLLOW-ON:NEW001', null, symbol,
                       participant_id, account_id, total_issue_quantity,
                       tradable_allocation_quantity, locked_allocation_quantity,
                       external_allocation_quantity, underwritten_quantity,
                       issue_price, underwriting_type, 'COMPLETED', policy_version,
                       ?, ?
                  from stock_underwriting_contract
                 where id = ?
                """,
                NOW,
                NOW,
                initialContractId
        );

        var recommendation = underwritingRecommendationService.getRecommendation();

        assertThat(recommendation.currentContractCount()).isEqualTo(1);
        assertThat(recommendation.recommendedRemainingContractCount()).isZero();
        assertThat(recommendation.symbols()).singleElement().satisfies(symbol -> {
            assertThat(symbol.symbol()).isEqualTo("NEW001");
            assertThat(symbol.existingContract()).isTrue();
            assertThat(symbol.creationEligible()).isFalse();
        });
    }

    @Test
    void create_underwritingContractBestEfforts_rejectsBecauseAllFloatIsTransferred() {
        service.createOrderBookInstrument(defaultRequest());
        persistPendingInstrumentRows();

        assertThatThrownBy(() -> underwritingProvisionService.createContract(
                "NEW001",
                new UnderwritingContractCreateRequest(
                        "BEST_EFFORTS",
                        "unsupported partial underwriting"
                ),
                "admin"
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("FIRM_COMMITMENT only");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_underwriting_contract",
                Integer.class
        )).isZero();
    }

    private void persistPendingInstrumentRows() {
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares,
                    tradable_shares, tick_size, price_limit_rate,
                    enabled, created_at, updated_at
                ) values (
                    'NEW001', 'New One', 'KOSPI', 10000.00, 100000,
                    50000, 50.00, 30.00,
                    true, ?, ?
                )
                """,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(
                    symbol, enabled, market_status, updated_at
                ) values ('NEW001', false, 'CLOSED', ?)
                """,
                NOW
        );
    }

    @Test
    void create_scaledRoleSeparatedIssueRejectsOutOfBandFloatBeforePersistence() {
        assertThatThrownBy(() -> service.createOrderBookInstrument(
                request(new BigDecimal("0.900000"))
        )).isInstanceOf(StockException.class)
                .hasMessageContaining("between 0.20 and 0.85");

        verify(instrumentRepository, never()).save(any());
        verify(corporateActionRepository, never()).save(any());
        verify(marketConfigRepository, never()).save(any());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_underwriting_contract",
                Integer.class
        )).isZero();
    }

    private OrderBookInstrumentRequest request(BigDecimal tradableShareRate) {
        return new OrderBookInstrumentRequest(
                "NEW001",
                "New One",
                "KOSPI",
                new BigDecimal("10000.00"),
                100_000L,
                new BigDecimal("30.00"),
                new InitialIssueAllocationRequest(
                        "SCALED_ROLE_SEPARATED",
                        tradableShareRate
                )
        );
    }

    private OrderBookInstrumentRequest defaultRequest() {
        return new OrderBookInstrumentRequest(
                "NEW001",
                "New One",
                "KOSPI",
                new BigDecimal("10000.00"),
                100_000L,
                new BigDecimal("30.00"),
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

    private SimulationClockSnapshot pausedClock() {
        return clock(false);
    }

    private SimulationClockSnapshot runningClock() {
        return clock(true);
    }

    private SimulationClockSnapshot clock(boolean running) {
        return new SimulationClockSnapshot(
                NOW.toLocalDate(),
                NOW,
                NOW.toLocalDate().atStartOfDay(),
                NOW,
                NOW.toLocalDate().atStartOfDay(),
                7_200,
                running,
                false,
                0L,
                running ? NOW : null,
                running ? NOW : null
        );
    }
}
