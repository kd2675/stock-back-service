package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlement;
import stock.back.service.database.entity.StockCorporateActionEntitlementStatus;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorporateActionQueryServiceTest {

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockCorporateActionRepository stockCorporateActionRepository;

    @Mock
    private StockAccountRepository stockAccountRepository;

    @Mock
    private StockCorporateActionEntitlementRepository stockCorporateActionEntitlementRepository;

    private CorporateActionQueryService service;

    @BeforeEach
    void setUp() {
        service = new CorporateActionQueryService(
                stockOrderBookInstrumentRepository,
                stockCorporateActionRepository,
                stockAccountRepository,
                stockCorporateActionEntitlementRepository
        );
    }

    @Test
    void getCorporateActions_existingSymbol_returnsMappedResponses() {
        StockCorporateAction action = mock(StockCorporateAction.class);
        LocalDate exRightsDate = LocalDate.of(2026, 6, 22);
        LocalDate paymentDate = LocalDate.of(2026, 6, 24);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 20, 9, 0);
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockCorporateActionRepository.findBySymbolOrderByCreatedAtDesc("ZQ001")).thenReturn(List.of(action));
        when(action.getId()).thenReturn(11L);
        when(action.getSymbol()).thenReturn("ZQ001");
        when(action.getActionType()).thenReturn(StockCorporateActionType.CASH_DIVIDEND);
        when(action.getDividendAmount()).thenReturn(new BigDecimal("1000.00"));
        when(action.getStatus()).thenReturn(StockCorporateActionStatus.ANNOUNCED);
        when(action.getBasePrice()).thenReturn(new BigDecimal("70000.00"));
        when(action.getTheoreticalExRightsPrice()).thenReturn(new BigDecimal("70000.00"));
        when(action.getExRightsDate()).thenReturn(exRightsDate);
        when(action.getPaymentDate()).thenReturn(paymentDate);
        when(action.getCreatedAt()).thenReturn(createdAt);

        var responses = service.getCorporateActions(" zq001 ");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo(11L);
        assertThat(responses.get(0).symbol()).isEqualTo("ZQ001");
        assertThat(responses.get(0).actionType()).isEqualTo(StockCorporateActionType.CASH_DIVIDEND);
        assertThat(responses.get(0).dividendAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(responses.get(0).subscribedShareQuantity()).isNull();
        assertThat(responses.get(0).remainingShareQuantity()).isNull();
        assertThat(responses.get(0).status()).isEqualTo(StockCorporateActionStatus.ANNOUNCED);
        assertThat(responses.get(0).exRightsDate()).isEqualTo(exRightsDate);
        assertThat(responses.get(0).paymentDate()).isEqualTo(paymentDate);
        assertThat(responses.get(0).createdAt()).isEqualTo(createdAt);
    }

    @Test
    void getCorporateActions_paidInCapitalIncrease_returnsSubscriptionProgress() {
        StockCorporateAction action = mock(StockCorporateAction.class);
        StockCorporateActionEntitlementRepository.SubscribedShareQuantitySummary summary =
                mock(StockCorporateActionEntitlementRepository.SubscribedShareQuantitySummary.class);
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockCorporateActionRepository.findBySymbolOrderByCreatedAtDesc("ZQ001")).thenReturn(List.of(action));
        when(action.getId()).thenReturn(11L);
        when(action.getSymbol()).thenReturn("ZQ001");
        when(action.getActionType()).thenReturn(StockCorporateActionType.PAID_IN_CAPITAL_INCREASE);
        when(action.getShareQuantity()).thenReturn(1000L);
        when(action.getStatus()).thenReturn(StockCorporateActionStatus.ANNOUNCED);
        when(stockCorporateActionEntitlementRepository.sumSubscribedShareQuantityByActionIdInAndStatusIn(
                List.of(11L),
                List.of(
                        StockCorporateActionEntitlementStatus.PARTIALLY_SUBSCRIBED,
                        StockCorporateActionEntitlementStatus.SUBSCRIBED,
                        StockCorporateActionEntitlementStatus.PAID
                )
        )).thenReturn(List.of(summary));
        when(summary.getActionId()).thenReturn(11L);
        when(summary.getSubscribedShareQuantity()).thenReturn(350L);

        var responses = service.getCorporateActions("zq001");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).shareQuantity()).isEqualTo(1000L);
        assertThat(responses.get(0).subscribedShareQuantity()).isEqualTo(350L);
        assertThat(responses.get(0).remainingShareQuantity()).isEqualTo(650L);
    }

    @Test
    void getCorporateActions_feedFiltersTypeAndAppliesBoundedPage() {
        StockCorporateAction action = mock(StockCorporateAction.class);
        when(stockCorporateActionRepository.findByActionTypeOrderByCreatedAtDescIdDesc(
                StockCorporateActionType.CASH_DIVIDEND,
                PageRequest.of(0, 25)
        )).thenReturn(List.of(action));
        when(action.getId()).thenReturn(11L);
        when(action.getActionType()).thenReturn(StockCorporateActionType.CASH_DIVIDEND);

        var responses = service.getCorporateActions(StockCorporateActionType.CASH_DIVIDEND, 25);

        assertThat(responses).extracting(response -> response.id()).containsExactly(11L);
        verify(stockCorporateActionRepository).findByActionTypeOrderByCreatedAtDescIdDesc(
                StockCorporateActionType.CASH_DIVIDEND,
                PageRequest.of(0, 25)
        );
        verify(stockCorporateActionRepository, never())
                .findByActionTypeAndStatusInOrderByCreatedAtDescIdDesc(
                        StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                        List.of(
                                StockCorporateActionStatus.ANNOUNCED,
                                StockCorporateActionStatus.EX_RIGHTS_APPLIED,
                                StockCorporateActionStatus.PAID
                        )
                );
    }

    @Test
    void getCorporateActions_paidInFeedActiveOutsideLimit_mergesDeduplicatesAndSorts() {
        StockCorporateAction newestListed = paidInAction(
                51L,
                StockCorporateActionStatus.LISTED,
                LocalDateTime.of(2026, 7, 5, 9, 0)
        );
        StockCorporateAction recentPaid = paidInAction(
                41L,
                StockCorporateActionStatus.PAID,
                LocalDateTime.of(2026, 7, 4, 9, 0)
        );
        StockCorporateAction olderPaid = paidInAction(
                31L,
                StockCorporateActionStatus.PAID,
                LocalDateTime.of(2026, 7, 3, 9, 0)
        );
        StockCorporateAction olderExRights = paidInAction(
                21L,
                StockCorporateActionStatus.EX_RIGHTS_APPLIED,
                LocalDateTime.of(2026, 7, 2, 9, 0)
        );
        StockCorporateAction oldestAnnounced = paidInAction(
                11L,
                StockCorporateActionStatus.ANNOUNCED,
                LocalDateTime.of(2026, 7, 1, 9, 0)
        );
        when(stockCorporateActionRepository.findByActionTypeOrderByCreatedAtDescIdDesc(
                StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                PageRequest.of(0, 2)
        )).thenReturn(List.of(newestListed, recentPaid));
        when(stockCorporateActionRepository.findByActionTypeAndStatusInOrderByCreatedAtDescIdDesc(
                StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                List.of(
                        StockCorporateActionStatus.ANNOUNCED,
                        StockCorporateActionStatus.EX_RIGHTS_APPLIED,
                        StockCorporateActionStatus.PAID
                )
        )).thenReturn(List.of(recentPaid, olderPaid, olderExRights, oldestAnnounced));
        when(stockCorporateActionEntitlementRepository.sumSubscribedShareQuantityByActionIdInAndStatusIn(
                List.of(51L, 41L, 31L, 21L, 11L),
                List.of(
                        StockCorporateActionEntitlementStatus.PARTIALLY_SUBSCRIBED,
                        StockCorporateActionEntitlementStatus.SUBSCRIBED,
                        StockCorporateActionEntitlementStatus.PAID
                )
        )).thenReturn(List.of());

        var responses = service.getCorporateActions(StockCorporateActionType.PAID_IN_CAPITAL_INCREASE, 2);

        assertThat(responses).extracting(response -> response.id(), response -> response.status())
                .containsExactly(
                        tuple(51L, StockCorporateActionStatus.LISTED),
                        tuple(41L, StockCorporateActionStatus.PAID),
                        tuple(31L, StockCorporateActionStatus.PAID),
                        tuple(21L, StockCorporateActionStatus.EX_RIGHTS_APPLIED),
                        tuple(11L, StockCorporateActionStatus.ANNOUNCED)
                );
    }

    @Test
    void getCorporateActions_unfilteredFeedActivePaidInOutsideLimit_isStillReturned() {
        StockCorporateAction recentDividend = mock(StockCorporateAction.class);
        StockCorporateAction olderPaidIn = paidInAction(
                11L,
                StockCorporateActionStatus.ANNOUNCED,
                LocalDateTime.of(2026, 7, 1, 9, 0)
        );
        when(recentDividend.getId()).thenReturn(41L);
        when(recentDividend.getActionType()).thenReturn(StockCorporateActionType.CASH_DIVIDEND);
        when(recentDividend.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 2, 9, 0));
        when(stockCorporateActionRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 1)))
                .thenReturn(List.of(recentDividend));
        when(stockCorporateActionRepository.findByActionTypeAndStatusInOrderByCreatedAtDescIdDesc(
                StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                List.of(
                        StockCorporateActionStatus.ANNOUNCED,
                        StockCorporateActionStatus.EX_RIGHTS_APPLIED,
                        StockCorporateActionStatus.PAID
                )
        )).thenReturn(List.of(olderPaidIn));
        when(stockCorporateActionEntitlementRepository.sumSubscribedShareQuantityByActionIdInAndStatusIn(
                List.of(11L),
                List.of(
                        StockCorporateActionEntitlementStatus.PARTIALLY_SUBSCRIBED,
                        StockCorporateActionEntitlementStatus.SUBSCRIBED,
                        StockCorporateActionEntitlementStatus.PAID
                )
        )).thenReturn(List.of());

        var responses = service.getCorporateActions(null, 1);

        assertThat(responses).extracting(response -> response.id()).containsExactly(41L, 11L);
    }

    @Test
    void getCorporateActions_feedLimitAboveMaximum_throwsBadRequestBeforeQuery() {
        assertThatThrownBy(() -> service.getCorporateActions(null, 201))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("between 1 and 200");

        verifyNoInteractions(stockCorporateActionRepository);
    }

    @Test
    void getCorporateActions_unknownSymbol_throwsNotFoundAndSkipsActionQuery() {
        when(stockOrderBookInstrumentRepository.existsById("UNKNOWN")).thenReturn(false);

        assertThatThrownBy(() -> service.getCorporateActions("unknown"))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Unknown order book symbol: UNKNOWN");

        verifyNoInteractions(stockCorporateActionRepository);
    }

    @Test
    void getMyCorporateActionEntitlements_withoutActiveAccount_returnsEmptyList() {
        when(stockAccountRepository.findByUserKeyAndStatus("user-a", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        var responses = service.getMyCorporateActionEntitlements("user-a");

        assertThat(responses).isEmpty();
        verifyNoInteractions(stockCorporateActionEntitlementRepository);
    }

    @Test
    void getMyCorporateActionEntitlements_existingRows_returnsJoinedActionType() {
        StockAccount account = mock(StockAccount.class);
        StockCorporateActionEntitlement entitlement = mock(StockCorporateActionEntitlement.class);
        StockCorporateAction action = mock(StockCorporateAction.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 22, 9, 0);
        when(stockAccountRepository.findByUserKeyAndStatus("user-a", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(account.getId()).thenReturn(101L);
        when(stockCorporateActionEntitlementRepository.findTop50ByAccountIdOrderByCreatedAtDesc(101L))
                .thenReturn(List.of(entitlement));
        when(entitlement.getId()).thenReturn(21L);
        when(entitlement.getAccountId()).thenReturn(101L);
        when(entitlement.getActionId()).thenReturn(11L);
        when(entitlement.getSymbol()).thenReturn("ZQ001");
        when(entitlement.getQuantity()).thenReturn(3L);
        when(entitlement.getShareQuantity()).thenReturn(1L);
        when(entitlement.getStatus()).thenReturn(StockCorporateActionEntitlementStatus.ANNOUNCED);
        when(entitlement.getCreatedAt()).thenReturn(createdAt);
        when(stockCorporateActionRepository.findAllById(List.of(11L))).thenReturn(List.of(action));
        when(action.getId()).thenReturn(11L);
        when(action.getActionType()).thenReturn(StockCorporateActionType.BONUS_ISSUE);

        var responses = service.getMyCorporateActionEntitlements("user-a");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo(21L);
        assertThat(responses.get(0).actionId()).isEqualTo(11L);
        assertThat(responses.get(0).symbol()).isEqualTo("ZQ001");
        assertThat(responses.get(0).actionType()).isEqualTo(StockCorporateActionType.BONUS_ISSUE);
        assertThat(responses.get(0).quantity()).isEqualTo(3L);
        assertThat(responses.get(0).shareQuantity()).isEqualTo(1L);
        assertThat(responses.get(0).status()).isEqualTo(StockCorporateActionEntitlementStatus.ANNOUNCED);
        assertThat(responses.get(0).createdAt()).isEqualTo(createdAt);
    }

    @Test
    void getMyCorporateActionEntitlements_activeRightOutsideRecentHistory_isStillReturned() {
        StockAccount account = mock(StockAccount.class);
        StockCorporateActionEntitlement activeEntitlement = mock(StockCorporateActionEntitlement.class);
        StockCorporateAction action = mock(StockCorporateAction.class);
        when(stockAccountRepository.findByUserKeyAndStatus("user-active-right", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(account.getId()).thenReturn(101L);
        when(stockCorporateActionEntitlementRepository.findByAccountIdAndStatusInOrderByCreatedAtDesc(
                101L,
                List.of(
                        StockCorporateActionEntitlementStatus.ANNOUNCED,
                        StockCorporateActionEntitlementStatus.PARTIALLY_SUBSCRIBED,
                        StockCorporateActionEntitlementStatus.SUBSCRIBED
                )
        )).thenReturn(List.of(activeEntitlement));
        when(stockCorporateActionEntitlementRepository.findTop50ByAccountIdOrderByCreatedAtDesc(101L))
                .thenReturn(List.of());
        when(activeEntitlement.getId()).thenReturn(51L);
        when(activeEntitlement.getActionId()).thenReturn(11L);
        when(activeEntitlement.getStatus()).thenReturn(StockCorporateActionEntitlementStatus.ANNOUNCED);
        when(stockCorporateActionRepository.findAllById(List.of(11L))).thenReturn(List.of(action));
        when(action.getId()).thenReturn(11L);

        var responses = service.getMyCorporateActionEntitlements("user-active-right");

        assertThat(responses).extracting(response -> response.id()).containsExactly(51L);
    }

    private StockCorporateAction paidInAction(
            Long id,
            StockCorporateActionStatus status,
            LocalDateTime createdAt
    ) {
        StockCorporateAction action = mock(StockCorporateAction.class);
        when(action.getId()).thenReturn(id);
        when(action.getActionType()).thenReturn(StockCorporateActionType.PAID_IN_CAPITAL_INCREASE);
        when(action.getShareQuantity()).thenReturn(100L);
        when(action.getStatus()).thenReturn(status);
        when(action.getCreatedAt()).thenReturn(createdAt);
        return action;
    }
}
