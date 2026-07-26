package stock.back.service.market.biz;

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
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.InitialIssueAllocationRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;
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
    private StockListingAutoAccountConfigRepository listingConfigRepository;
    private StockOrderBookMarketConfigRepository marketConfigRepository;
    private OrderBookInstrumentCommandService service;

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
        listingConfigRepository = mock(StockListingAutoAccountConfigRepository.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
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

        service = new OrderBookInstrumentCommandService(
                virtualInstrumentRepository,
                priceRepository,
                autoMarketConfigRepository,
                instrumentRepository,
                marketConfigRepository,
                corporateActionRepository,
                listingConfigRepository,
                jdbcTemplate,
                simulationClockService,
                marketSessionService,
                freezeGuard
        );
    }

    @Test
    void create_defaultIssueSplitsFloatAndLockedSharesWithoutLegacyLiquidity() {
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
                 order by account.participant_category
                """
        )).containsExactly(
                Map.of(
                        "user_key", "stock-issue-underwriter-new001",
                        "account_code", "UW-NEW001",
                        "participant_category", "ISSUE_UNDERWRITER",
                        "self_trade_group_id", "ISSUE_UNDERWRITER:DEFAULT",
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
                 where account.user_key = 'stock-issuance-lockup-new001'
                """
        )).containsEntry("account_role", "SYSTEM_CUSTODY")
                .containsEntry("desk_code", "ISSUANCE_LOCKUP:NEW001");
        assertThat(jdbcTemplate.queryForMap(
                """
                select total_issue_quantity, tradable_allocation_quantity,
                       locked_allocation_quantity, external_allocation_quantity,
                       underwritten_quantity, underwriting_type, status,
                       stabilization_quantity_limit, stabilization_amount_limit
                  from stock_underwriting_contract
                 where symbol = 'NEW001'
                """
        )).containsEntry("total_issue_quantity", 100_000L)
                .containsEntry("tradable_allocation_quantity", 50_000L)
                .containsEntry("locked_allocation_quantity", 50_000L)
                .containsEntry("external_allocation_quantity", 0L)
                .containsEntry("underwritten_quantity", 50_000L)
                .containsEntry("underwriting_type", "FIRM_COMMITMENT")
                .containsEntry("status", "ALLOCATED")
                .containsEntry("stabilization_quantity_limit", 0L)
                .containsEntry("stabilization_amount_limit", new BigDecimal("0.00"));
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
                                "INITIAL_FLOAT_UNDERWRITER",
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
                "select count(*) from stock_listing_auto_account_config where symbol = 'NEW001'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForMap(
                """
                select policy_scope, scope_key, version_no, status,
                       change_reason, changed_by
                  from stock_market_policy_version
                 where policy_scope = 'UNDERWRITING_CONTRACT'
                   and scope_key = 'INITIAL-ISSUE:NEW001'
                """
        )).containsEntry("policy_scope", "UNDERWRITING_CONTRACT")
                .containsEntry("scope_key", "INITIAL-ISSUE:NEW001")
                .containsEntry("version_no", 1L)
                .containsEntry("status", "ACTIVE")
                .containsEntry(
                        "change_reason",
                        "Create inactive role-separated underwriting contract"
                )
                .containsEntry("changed_by", "SYSTEM_INITIAL_ISSUE");
        verify(listingConfigRepository, never()).save(any());
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
                null,
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
        return new SimulationClockSnapshot(
                NOW.toLocalDate(),
                NOW,
                NOW.toLocalDate().atStartOfDay(),
                NOW,
                NOW.toLocalDate().atStartOfDay(),
                7_200,
                false,
                false,
                0L,
                null,
                null
        );
    }
}
