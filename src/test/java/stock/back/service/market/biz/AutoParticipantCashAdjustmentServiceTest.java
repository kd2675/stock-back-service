package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoParticipantCashAdjustmentServiceTest {

    @Mock
    private StockAutoParticipantRepository stockAutoParticipantRepository;

    @Mock
    private StockAccountRepository stockAccountRepository;

    @Mock
    private StockAccountCashFlowRepository stockAccountCashFlowRepository;

    @Mock
    private SimulationClockService simulationClockService;

    @Mock
    private MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    private AutoParticipantCashAdjustmentService service;

    @BeforeEach
    void setUp() {
        lenient().when(simulationClockService.currentMarketDateTime()).thenReturn(LocalDateTime.of(2026, 7, 1, 10, 0));
        service = new AutoParticipantCashAdjustmentService(
                stockAutoParticipantRepository,
                stockAccountRepository,
                stockAccountCashFlowRepository,
                simulationClockService,
                marketLedgerFreezeGuard
        );
    }

    @Test
    void adjustAutoParticipantCash_deposit_updatesActiveAccountBalanceAndRecordsLedger() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        StockAccount account = StockAccount.open("stock-auto-001");
        account.depositCash(new BigDecimal("10000000.00"));
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("stock-auto-001", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));

        var response = service.adjustAutoParticipantCash(
                " stock-auto-001 ",
                new AutoParticipantCashAdjustmentRequest("deposit", new BigDecimal("1000000.00")),
                " stock-admin "
        );

        assertThat(response.userKey()).isEqualTo("stock-auto-001");
        assertThat(response.adjustmentType()).isEqualTo("DEPOSIT");
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("1000000.00"));
        assertThat(response.cashBalance()).isEqualByComparingTo(new BigDecimal("11000000.00"));
        verify(stockAccountCashFlowRepository).save(any(StockAccountCashFlow.class));
    }

    @Test
    void adjustAutoParticipantCash_withdrawWithoutEnoughCash_throwsBadRequestAndDoesNotRecordLedger() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        StockAccount account = StockAccount.open("stock-auto-001");
        account.depositCash(new BigDecimal("10000000.00"));
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("stock-auto-001", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.adjustAutoParticipantCash(
                "stock-auto-001",
                new AutoParticipantCashAdjustmentRequest("WITHDRAW", new BigDecimal("999999999.00")),
                "stock-admin"
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Insufficient auto participant cash balance");

        verify(stockAccountCashFlowRepository, never()).save(any());
    }

    @Test
    void adjustAutoParticipantCash_withWithdrawnParticipant_throwsNotFound() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        participant.withdraw();
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> service.adjustAutoParticipantCash(
                "stock-auto-001",
                new AutoParticipantCashAdjustmentRequest("DEPOSIT", new BigDecimal("1000.00")),
                "stock-admin"
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown auto participant");

        verify(stockAccountRepository, never()).findByUserKeyAndStatusForUpdate(any(), any());
    }
}
