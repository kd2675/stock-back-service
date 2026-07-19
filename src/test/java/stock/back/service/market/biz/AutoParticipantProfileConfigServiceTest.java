package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.database.repository.StockAutoParticipantProfileConfigRepository;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoParticipantProfileConfigServiceTest {

    @Mock
    private StockAutoParticipantProfileConfigRepository stockAutoParticipantProfileConfigRepository;

    private AutoParticipantProfileConfigService service;

    @BeforeEach
    void setUp() {
        service = new AutoParticipantProfileConfigService(stockAutoParticipantProfileConfigRepository);
    }

    @Test
    void updateAutoParticipantProfileConfig_dividendReinvestorClearsRecurringDeposit() {
        when(stockAutoParticipantProfileConfigRepository.findById(AutoParticipantProfileType.DIVIDEND_REINVESTOR))
                .thenReturn(Optional.empty());
        when(stockAutoParticipantProfileConfigRepository.save(any(StockAutoParticipantProfileConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateAutoParticipantProfileConfig(
                "DIVIDEND_REINVESTOR",
                validRequest()
        );

        assertThat(response.profileType()).isEqualTo("DIVIDEND_REINVESTOR");
        assertThat(response.recurringDepositAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.recurringDepositIntervalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.recurringDepositIntervalUnit()).isEqualTo("DAY");
    }

    @Test
    void updateAutoParticipantProfileConfig_nullRequest_throwsBadRequestBeforeLookup() {
        assertThatThrownBy(() -> service.updateAutoParticipantProfileConfig("NEWS_REACTIVE", null))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Auto participant profile config update is required");

        verify(stockAutoParticipantProfileConfigRepository, never()).findById(any());
        verify(stockAutoParticipantProfileConfigRepository, never()).save(any());
    }

    private AutoParticipantProfileConfigRequest validRequest() {
        return new AutoParticipantProfileConfigRequest(
                new BigDecimal("0.70"),
                new BigDecimal("0.45"),
                new BigDecimal("0.20"),
                new BigDecimal("0.30"),
                new BigDecimal("0.30"),
                new BigDecimal("0.10"),
                new BigDecimal("0.20"),
                new BigDecimal("0.10"),
                new BigDecimal("0.05"),
                new BigDecimal("0.35"),
                new BigDecimal("1.10"),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("0.60"),
                new BigDecimal("0.40"),
                new BigDecimal("0.20"),
                new BigDecimal("50000.00"),
                new BigDecimal("30"),
                "DAY",
                null
        );
    }
}
