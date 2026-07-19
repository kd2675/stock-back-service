package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.market.vo.CorporateActionSubscriptionRequest;
import web.common.core.simulation.SimulationMarketSession;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorporateActionSubscriptionServiceTest {

    @Mock
    private StockCorporateActionRepository stockCorporateActionRepository;

    @Mock
    private StockCorporateActionEntitlementRepository stockCorporateActionEntitlementRepository;

    @Mock
    private StockAccountRepository stockAccountRepository;

    @Mock
    private StockAccountCashFlowRepository stockAccountCashFlowRepository;

    @Mock
    private SimulationClockService simulationClockService;

    @Mock
    private SimulationMarketSessionService simulationMarketSessionService;

    @Mock
    private JdbcClient jdbcClient;

    @Mock
    private MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    private CorporateActionSubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new CorporateActionSubscriptionService(
                stockCorporateActionRepository,
                stockCorporateActionEntitlementRepository,
                stockAccountRepository,
                stockAccountCashFlowRepository,
                simulationClockService,
                simulationMarketSessionService,
                jdbcClient,
                marketLedgerFreezeGuard
        );
    }

    @Test
    void subscribe_regularSession_throwsConflictBeforeLockingRows() {
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);

        assertThatThrownBy(() -> service.subscribe(
                1L,
                new CorporateActionSubscriptionRequest(10L),
                "user-001"
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("only available after market close");

        verifyNoInteractions(
                stockCorporateActionRepository,
                stockCorporateActionEntitlementRepository,
                stockAccountRepository,
                stockAccountCashFlowRepository,
                simulationClockService,
                jdbcClient
        );
    }
}
