package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.RecurringCashIntervalUnit;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoParticipantProfileConfigCommandTest {

    @Test
    void from_dividendReinvestor_clearsRecurringDeposit() {
        AutoParticipantProfileConfigCommand command = AutoParticipantProfileConfigCommand.from(
                AutoParticipantProfileType.DIVIDEND_REINVESTOR,
                validRequest()
        );

        assertThat(command.recurringDepositAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(command.recurringDepositIntervalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(command.recurringDepositIntervalUnit()).isEqualTo(RecurringCashIntervalUnit.DAY);
    }

    @Test
    void from_orderTtlMultiplierBelowMinimum_throwsBadRequest() {
        AutoParticipantProfileConfigRequest request = new AutoParticipantProfileConfigRequest(
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
                new BigDecimal("0.09"),
                BigDecimal.ONE,
                new BigDecimal("0.60"),
                new BigDecimal("0.40"),
                new BigDecimal("0.20"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "DAY",
                null
        );

        assertThatThrownBy(() -> AutoParticipantProfileConfigCommand.from(AutoParticipantProfileType.NEWS_REACTIVE, request))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Order TTL multiplier must be between 0.1 and 10");
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
