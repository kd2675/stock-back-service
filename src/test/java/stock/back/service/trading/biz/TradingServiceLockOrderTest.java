package stock.back.service.trading.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockHolding;
import stock.back.service.database.entity.StockOrder;
import stock.back.service.database.repository.StockOrderRepository;

class TradingServiceLockOrderTest {

    @Test
    void cancelOrder_sellOrder_locksAccountAndHoldingBeforeOrderPrimaryKey() {
        AccountService accountService = mock(AccountService.class);
        StockOrderRepository orderRepository = mock(StockOrderRepository.class);
        TradingReservationService reservationService = mock(TradingReservationService.class);
        TradingSessionFenceService fenceService = mock(TradingSessionFenceService.class);
        TradingService service = new TradingService(
                accountService,
                orderRepository,
                mock(TradingQueryService.class),
                mock(TradingMarketRuleService.class),
                reservationService,
                fenceService,
                mock(OrderBookReadySymbolPublisher.class)
        );
        LocalDateTime approvedAt = LocalDateTime.of(2026, 7, 15, 17, 59, 59);
        TradingSessionFenceService.OwnedOrderSessionApproval approval =
                new TradingSessionFenceService.OwnedOrderSessionApproval(
                        "LOCK001",
                        MarketType.ORDER_BOOK,
                        OrderSide.SELL,
                        LocalDate.of(2026, 7, 15),
                        9L,
                        approvedAt
                );
        StockAccount account = StockAccount.open("lock-order-user");
        ReflectionTestUtils.setField(account, "id", 10L);
        StockHolding holding = mock(StockHolding.class);
        StockOrder order = StockOrder.pending(
                "lock-order-client-id",
                10L,
                "LOCK001",
                MarketType.ORDER_BOOK,
                OrderSide.SELL,
                OrderType.LIMIT,
                new BigDecimal("1000.00"),
                5L,
                BigDecimal.ZERO,
                approvedAt
        );
        ReflectionTestUtils.setField(order, "id", 20L);
        when(fenceService.acquireOwnedOpenOrderMutationSession(
                "lock-order-user",
                20L,
                "Only pending orders can be cancelled"
        )).thenReturn(approval);
        when(accountService.requireAccountForUpdate("lock-order-user")).thenReturn(account);
        when(reservationService.findSellHoldingForUpdate(10L, "LOCK001")).thenReturn(holding);
        when(orderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));

        var response = service.cancelOrder("lock-order-user", 20L);

        assertThat(response.id()).isEqualTo(20L);
        InOrder lockOrder = inOrder(accountService, reservationService, orderRepository);
        lockOrder.verify(accountService).requireAccountForUpdate("lock-order-user");
        lockOrder.verify(reservationService).findSellHoldingForUpdate(10L, "LOCK001");
        lockOrder.verify(orderRepository).findByIdForUpdate(20L);
        verify(reservationService).releaseOnCancel(account, holding, order, approvedAt);
    }
}
