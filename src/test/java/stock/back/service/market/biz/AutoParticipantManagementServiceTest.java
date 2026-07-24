package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountParticipantCategory;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoParticipantProfileConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.market.vo.AutoParticipantRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoParticipantManagementServiceTest {

    @Mock
    private StockAutoParticipantRepository stockAutoParticipantRepository;

    @Mock
    private StockAutoParticipantProfileConfigRepository stockAutoParticipantProfileConfigRepository;

    @Mock
    private StockAccountRepository stockAccountRepository;

    @Mock
    private StockAccountCashFlowRepository stockAccountCashFlowRepository;

    @Mock
    private AutoParticipantStrategyTransitionService strategyTransitionService;

    @Mock
    private AutoParticipantWithdrawalSettlementService withdrawalSettlementService;

    @Mock
    private SimulationClockService simulationClockService;

    @Mock
    private MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    private AutoParticipantManagementService service;

    @BeforeEach
    void setUp() {
        lenient().when(simulationClockService.currentMarketDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 2, 10, 0));
        service = new AutoParticipantManagementService(
                stockAutoParticipantRepository,
                stockAutoParticipantProfileConfigRepository,
                stockAccountRepository,
                stockAccountCashFlowRepository,
                strategyTransitionService,
                withdrawalSettlementService,
                simulationClockService,
                marketLedgerFreezeGuard
        );
    }

    @Test
    void upsertAutoParticipant_existingParticipant_updatesProfileOnly() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-001")).thenReturn(Optional.empty());

        var response = service.upsertAutoParticipant(
                "stock-auto-001",
                new AutoParticipantRequest("자동 참여자 수정", false, "NEWS_REACTIVE")
        );

        assertThat(response.displayName()).isEqualTo("자동 참여자 수정");
        assertThat(response.enabled()).isFalse();
        assertThat(response.profileType()).isEqualTo("NEWS_REACTIVE");
        verify(stockAccountRepository, never()).save(any(StockAccount.class));
        verify(stockAccountCashFlowRepository, never()).save(any(StockAccountCashFlow.class));
    }

    @Test
    void upsertAutoParticipant_createAccountAndInitialCash_opensAccountAndRecordsCashFlow() {
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.empty());
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-001")).thenReturn(Optional.empty());
        when(stockAccountRepository.save(any(StockAccount.class)))
                .thenAnswer(invocation -> {
                    StockAccount account = invocation.getArgument(0);
                    setAccountId(account, 101L);
                    return account;
                });

        var response = service.upsertAutoParticipant(
                "stock-auto-001",
                new AutoParticipantRequest(
                        "자동 참여자 1",
                        true,
                        "NEWS_REACTIVE",
                        new BigDecimal("100000"),
                        BigDecimal.ONE,
                        "DAY",
                        true,
                        new BigDecimal("5000000")
                ),
                "admin-user"
        );

        assertThat(response.accountId()).isEqualTo(101L);
        assertThat(response.accountStatus()).isEqualTo("ACTIVE");
        assertThat(response.cashBalance()).isEqualByComparingTo("5000000");
        verify(stockAccountRepository, never()).findByUserKeyAndStatusForUpdate("stock-auto-001", StockAccountStatus.ACTIVE);
        verify(stockAccountCashFlowRepository).save(argThat(cashFlow ->
                cashFlow.getAccountId().equals(101L)
                        && cashFlow.getAmount().compareTo(new BigDecimal("5000000")) == 0
                        && "admin-user".equals(cashFlow.getCreatedBy())
                        && cashFlow.getCreatedAt().equals(LocalDateTime.of(2026, 7, 2, 10, 0))
        ));
    }

    @Test
    void upsertAutoParticipant_initialCashWithoutCreateAccount_opensAccountBecauseCashRequiresAccount() {
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.empty());
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-001")).thenReturn(Optional.empty());
        when(stockAccountRepository.save(any(StockAccount.class)))
                .thenAnswer(invocation -> {
                    StockAccount account = invocation.getArgument(0);
                    setAccountId(account, 102L);
                    return account;
                });

        var response = service.upsertAutoParticipant(
                "stock-auto-001",
                new AutoParticipantRequest(
                        "자동 참여자 1",
                        true,
                        "NOISE_TRADER",
                        null,
                        null,
                        null,
                        false,
                        new BigDecimal("1000000")
                )
        );

        assertThat(response.accountId()).isEqualTo(102L);
        assertThat(response.cashBalance()).isEqualByComparingTo("1000000");
    }

    @Test
    void upsertAutoParticipant_existingAccountWithInitialCash_locksAccountAndRecordsCashFlow() {
        StockAccount account = StockAccount.open("stock-auto-001");
        setAccountId(account, 103L);
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.empty());
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-001")).thenReturn(Optional.of(account));
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("stock-auto-001", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(stockAccountRepository.save(any(StockAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.upsertAutoParticipant(
                "stock-auto-001",
                new AutoParticipantRequest(
                        "자동 참여자 1",
                        true,
                        "NOISE_TRADER",
                        null,
                        null,
                        null,
                        true,
                        new BigDecimal("2500000")
                )
        );

        assertThat(response.accountId()).isEqualTo(103L);
        assertThat(response.cashBalance()).isEqualByComparingTo("2500000");
        verify(stockAccountRepository).findByUserKeyAndStatusForUpdate("stock-auto-001", StockAccountStatus.ACTIVE);
        verify(stockAccountCashFlowRepository).save(argThat(cashFlow ->
                cashFlow.getAccountId().equals(103L)
                        && cashFlow.getAmount().compareTo(new BigDecimal("2500000")) == 0
        ));
    }

    @Test
    void upsertAutoParticipant_existingManualAccountWithoutFunding_assignsAutoParticipantRole() {
        StockAccount account = StockAccount.open("stock-auto-001");
        setAccountId(account, 104L);
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.empty());
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-001")).thenReturn(Optional.of(account));
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate("stock-auto-001", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(stockAccountRepository.save(any(StockAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.upsertAutoParticipant(
                "stock-auto-001",
                new AutoParticipantRequest(
                        "자동 참여자 1",
                        true,
                        "NOISE_TRADER",
                        null,
                        null,
                        null,
                        false,
                        BigDecimal.ZERO
                )
        );

        assertThat(account.getParticipantCategory()).isEqualTo(StockAccountParticipantCategory.AUTO_PARTICIPANT);
        verify(stockAccountRepository).save(account);
    }

    @Test
    void upsertAutoParticipant_invalidProfileType_throwsBadRequest() {
        assertThatThrownBy(() -> service.upsertAutoParticipant(
                "stock-auto-001",
                new AutoParticipantRequest("자동 참여자 수정", true, "UNKNOWN_PROFILE")
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown auto participant profile type");

        verify(stockAutoParticipantRepository, never()).save(any());
    }

    @Test
    void upsertAutoParticipant_newParticipant_usesProfileModelAndPreservesSeed() {
        when(stockAutoParticipantRepository.findById("stock-auto-v2")).thenReturn(Optional.empty());
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-v2")).thenReturn(Optional.empty());

        var response = service.upsertAutoParticipant(
                "stock-auto-v2",
                new AutoParticipantRequest(
                        "V2 참여자",
                        true,
                        "NOISE_TRADER",
                        null,
                        null,
                        null,
                        "987654321",
                        false,
                        BigDecimal.ZERO
                )
        );

        assertThat(response.behaviorModelVersion()).isEqualTo("V2");
        assertThat(response.behaviorSeed()).isEqualTo("987654321");
    }

    @Test
    void upsertAutoParticipant_strategyChange_retiresOldOrdersAndPurposeBudgetsBeforeUpdate() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-transition",
                "전환 참여자",
                true,
                AutoParticipantProfileType.PAYDAY_ACCUMULATOR
        );
        participant.update(
                "전환 참여자",
                true,
                AutoParticipantProfileType.PAYDAY_ACCUMULATOR,
                101L,
                null,
                null,
                null
        );
        StockAccount account = StockAccount.open("stock-auto-transition");
        setAccountId(account, 101L);
        when(stockAutoParticipantRepository.findById("stock-auto-transition"))
                .thenReturn(Optional.of(participant));
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKeyAndStatusForUpdate(
                "stock-auto-transition",
                StockAccountStatus.ACTIVE
        )).thenReturn(Optional.of(account));
        when(stockAccountRepository.findByUserKey("stock-auto-transition"))
                .thenReturn(Optional.of(account));

        service.upsertAutoParticipant(
                "stock-auto-transition",
                new AutoParticipantRequest(
                        "전환 참여자",
                        true,
                        "NOISE_TRADER",
                        null,
                        null,
                        null,
                        "101",
                        false,
                        BigDecimal.ZERO
                )
        );

        verify(marketLedgerFreezeGuard).acquireMutationPermit("auto-participant strategy transition");
        verify(strategyTransitionService).retireOpenOrdersAndFundingBudgets(
                account,
                LocalDateTime.of(2026, 7, 2, 10, 0)
        );
        assertThat(participant.getProfileType()).isEqualTo(AutoParticipantProfileType.NOISE_TRADER);
    }

    @Test
    void withdrawAutoParticipant_settlesAssetsAndClosesAccount() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        StockAccount account = StockAccount.open("stock-auto-001");
        account.assignAccountCodeIfMissing("AC001");
        setAccountId(account, 101L);
        when(stockAutoParticipantRepository.findByUserKeyForUpdate("stock-auto-001"))
                .thenReturn(Optional.of(participant));
        when(withdrawalSettlementService.findCompletedSettlement("stock-auto-001"))
                .thenReturn(Optional.empty());
        when(withdrawalSettlementService.settle(
                "stock-auto-001",
                "stock-admin",
                LocalDateTime.of(2026, 7, 2, 10, 0)
        )).thenAnswer(invocation -> {
            account.withdrawCash(account.getCashBalance(), LocalDateTime.of(2026, 7, 2, 10, 0));
            account.closeForAutoParticipantWithdrawal(LocalDateTime.of(2026, 7, 2, 10, 0));
            return new AutoParticipantWithdrawalSettlementService.WithdrawalSettlement(
                    new BigDecimal("5000000.00"),
                    125L,
                    2,
                    true
            );
        });
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-001")).thenReturn(Optional.of(account));
        account.depositCash(new BigDecimal("5000000.00"));

        var response = service.withdrawAutoParticipant("stock-auto-001", "stock-admin");

        assertThat(response.enabled()).isFalse();
        assertThat(response.withdrawnAt()).isNotNull();
        assertThat(response.accountStatus()).isEqualTo("CLOSED");
        assertThat(response.cashBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.withdrawalReturnedCashAmount()).isEqualByComparingTo("5000000.00");
        assertThat(response.withdrawalReturnedShareQuantity()).isEqualTo(125L);
        assertThat(response.withdrawalReturnedSymbolCount()).isEqualTo(2);
        assertThat(response.accountClosedOnWithdrawal()).isTrue();
        verify(withdrawalSettlementService).settle(
                "stock-auto-001",
                "stock-admin",
                LocalDateTime.of(2026, 7, 2, 10, 0)
        );
    }

    @Test
    void withdrawAutoParticipant_completedSettlement_returnsAuditWithoutSettlingTwice() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "탈퇴 완료 참여자",
                true
        );
        participant.withdraw();
        StockAccount account = StockAccount.open("stock-auto-001");
        setAccountId(account, 101L);
        account.assignParticipantCategory(
                StockAccountParticipantCategory.AUTO_PARTICIPANT,
                LocalDateTime.of(2026, 7, 2, 10, 0)
        );
        account.closeForAutoParticipantWithdrawal(LocalDateTime.of(2026, 7, 2, 10, 0));
        when(stockAutoParticipantRepository.findByUserKeyForUpdate("stock-auto-001"))
                .thenReturn(Optional.of(participant));
        when(withdrawalSettlementService.findCompletedSettlement("stock-auto-001"))
                .thenReturn(Optional.of(
                        new AutoParticipantWithdrawalSettlementService.WithdrawalSettlement(
                                new BigDecimal("1000000.00"),
                                25L,
                                1,
                                true
                        )
                ));
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-001"))
                .thenReturn(Optional.of(account));

        var response = service.withdrawAutoParticipant("stock-auto-001", "stock-admin");

        assertThat(response.withdrawalReturnedCashAmount()).isEqualByComparingTo("1000000.00");
        assertThat(response.withdrawalReturnedShareQuantity()).isEqualTo(25L);
        assertThat(response.accountClosedOnWithdrawal()).isTrue();
        verify(withdrawalSettlementService, never()).settle(any(), any(), any());
    }

    @Test
    void upsertAutoParticipant_withdrawnParticipant_rejectsReactivation() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "탈퇴 참여자",
                true
        );
        participant.withdraw();
        when(stockAutoParticipantRepository.findById("stock-auto-001"))
                .thenReturn(Optional.of(participant));

        AutoParticipantRequest request = new AutoParticipantRequest(
                "재활성화 참여자",
                true,
                "MOMENTUM_FOLLOWER",
                null,
                null,
                null,
                null,
                false,
                BigDecimal.ZERO
        );

        assertThatThrownBy(() -> service.upsertAutoParticipant("stock-auto-001", request))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("cannot be reactivated");

        verify(stockAutoParticipantRepository, never()).save(any());
        verifyNoInteractions(marketLedgerFreezeGuard);
    }

    private void setAccountId(StockAccount account, Long id) {
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", id);
    }
}
