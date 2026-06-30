package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.market.vo.AutoParticipantRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoParticipantManagementServiceTest {

    @Mock
    private StockAutoParticipantRepository stockAutoParticipantRepository;

    @Mock
    private StockAccountRepository stockAccountRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AutoParticipantManagementService service;

    @BeforeEach
    void setUp() {
        service = new AutoParticipantManagementService(
                stockAutoParticipantRepository,
                stockAccountRepository,
                jdbcTemplate
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
    void withdrawAutoParticipant_openOrders_releasesReservationsAndCancelsOrders() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        StockAccount account = StockAccount.open("stock-auto-001");
        account.assignAccountCodeIfMissing("AC001");
        setAccountId(account, 101L);
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockAccountRepository.findByUserKeyAndStatus("stock-auto-001", StockAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(stockAutoParticipantRepository.save(any(StockAutoParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockAccountRepository.findByUserKey("stock-auto-001")).thenReturn(Optional.of(account));
        when(jdbcTemplate.queryForList(any(String.class), org.mockito.ArgumentMatchers.eq(101L)))
                .thenReturn(List.of(
                        Map.of(
                                "id", 1L,
                                "symbol", "ZQ001",
                                "side", "BUY",
                                "quantity", 10L,
                                "filled_quantity", 0L,
                                "reserved_cash", new BigDecimal("10000.00")
                        ),
                        Map.of(
                                "id", 2L,
                                "symbol", "ZQ001",
                                "side", "SELL",
                                "quantity", 20L,
                                "filled_quantity", 5L,
                                "reserved_cash", BigDecimal.ZERO
                        )
                ));

        var response = service.withdrawAutoParticipant("stock-auto-001");

        assertThat(response.enabled()).isFalse();
        assertThat(response.withdrawnAt()).isNotNull();
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.eq("update stock_account set cash_balance = cash_balance + ?, updated_at = ? where id = ?"),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("10000.00")),
                any(),
                org.mockito.ArgumentMatchers.eq(101L)
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("update stock_holding"),
                org.mockito.ArgumentMatchers.eq(15L),
                org.mockito.ArgumentMatchers.eq(15L),
                any(),
                org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq("ZQ001")
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.eq("update stock_order set status = 'CANCELLED', reserved_cash = 0, updated_at = ? where id = ?"),
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(1L)
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.eq("update stock_order set status = 'CANCELLED', reserved_cash = 0, updated_at = ? where id = ?"),
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(2L)
        );
    }

    private void setAccountId(StockAccount account, Long id) {
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", id);
    }
}
