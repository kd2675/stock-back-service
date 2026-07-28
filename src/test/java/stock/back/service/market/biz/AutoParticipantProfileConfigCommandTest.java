package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.AutoParticipantBehaviorModelVersion;
import stock.back.service.database.entity.AutoParticipantProfileExitMode;
import stock.back.service.database.entity.AutoParticipantProfileInventoryMode;
import stock.back.service.database.entity.AutoParticipantProfilePricingMode;
import stock.back.service.database.entity.RecurringCashIntervalUnit;
import stock.back.service.market.vo.AutoParticipantFundingPolicyRequest;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoParticipantProfileConfigCommandTest {

    @Test
    void from_missingBehaviorModelVersion_defaultsProfileToV3() {
        AutoParticipantProfileConfigCommand command = AutoParticipantProfileConfigCommand.from(
                AutoParticipantProfileType.NOISE_TRADER,
                validRequest()
        );

        assertThat(command.behaviorModelVersion()).isEqualTo(AutoParticipantBehaviorModelVersion.V3);
    }

    @Test
    void from_removedBehaviorModelVersion_rejectsRequest() {
        assertThatThrownBy(() -> AutoParticipantProfileConfigCommand.from(
                        AutoParticipantProfileType.NOISE_TRADER,
                        withBehaviorModelVersion(validRequest(), "V2")
                ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Behavior model version is invalid");
    }

    @Test
    void from_dividendReinvestor_clearsRecurringDeposit() {
        AutoParticipantProfileConfigCommand command = AutoParticipantProfileConfigCommand.from(
                AutoParticipantProfileType.DIVIDEND_REINVESTOR,
                validRequest()
        );

        assertThat(command.fundingPolicy())
                .extracting(
                        AutoParticipantFundingPolicyCommand::recurringDepositAmount,
                        AutoParticipantFundingPolicyCommand::recurringDepositIntervalValue,
                        AutoParticipantFundingPolicyCommand::recurringDepositIntervalUnit
                )
                .containsExactly(BigDecimal.ZERO, BigDecimal.ZERO, RecurringCashIntervalUnit.DAY);
    }

    @Test
    void from_nestedFundingPolicy_takesPrecedenceOverLegacyFlatFields() {
        AutoParticipantProfileConfigCommand command = AutoParticipantProfileConfigCommand.from(
                AutoParticipantProfileType.PAYDAY_ACCUMULATOR,
                requestWithNestedFundingPolicy()
        );

        assertThat(command.fundingPolicy())
                .extracting(
                        AutoParticipantFundingPolicyCommand::recurringDepositAmount,
                        AutoParticipantFundingPolicyCommand::recurringDepositIntervalValue,
                        AutoParticipantFundingPolicyCommand::recurringDepositIntervalUnit
                )
                .containsExactly(
                        new BigDecimal("7000000.00"),
                        new BigDecimal("2.0000"),
                        RecurringCashIntervalUnit.DAY
                );
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

    @Test
    void from_pricePressureSensitivityAboveMaximum_throwsBadRequest() {
        AutoParticipantProfileConfigRequest request = validRequestWithPricePressureSensitivity(new BigDecimal("2.01"));

        assertThatThrownBy(() -> AutoParticipantProfileConfigCommand.from(AutoParticipantProfileType.NEWS_REACTIVE, request))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Price pressure sensitivity must be between 0 and 2");
    }

    @Test
    void from_missingExecutionPolicy_usesProfileDefaultsInsteadOfLegacyFields() {
        AutoParticipantProfileConfigCommand command = AutoParticipantProfileConfigCommand.from(
                AutoParticipantProfileType.NOISE_TRADER,
                requestWithLegacyModeWeights()
        );

        assertThat(command.pricingMode()).isEqualTo(AutoParticipantProfilePricingMode.DIRECTIONAL);
        assertThat(command.exitMode()).isEqualTo(AutoParticipantProfileExitMode.SIGNAL_DRIVEN);
        assertThat(command.inventoryMode()).isEqualTo(AutoParticipantProfileInventoryMode.SIGNAL_DRIVEN);
        assertThat(command.decisionFrequencyMultiplier()).isEqualByComparingTo("1.0000");
        assertThat(command.ordersPerDecisionMultiplier()).isEqualByComparingTo("1.0");
    }

    private AutoParticipantProfileConfigRequest validRequest() {
        return validRequestWithPricePressureSensitivity(BigDecimal.ONE);
    }

    private AutoParticipantProfileConfigRequest validRequestWithPricePressureSensitivity(BigDecimal pricePressureSensitivity) {
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
                pricePressureSensitivity,
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

    private AutoParticipantProfileConfigRequest requestWithLegacyModeWeights() {
        return new AutoParticipantProfileConfigRequest(
                new BigDecimal("0.70"),
                new BigDecimal("0.45"),
                new BigDecimal("0.20"),
                new BigDecimal("0.30"),
                new BigDecimal("0.30"),
                new BigDecimal("0.95"),
                new BigDecimal("0.20"),
                new BigDecimal("0.10"),
                new BigDecimal("0.05"),
                new BigDecimal("0.35"),
                new BigDecimal("1.10"),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("0.95"),
                new BigDecimal("0.40"),
                new BigDecimal("0.95"),
                new BigDecimal("50000.00"),
                new BigDecimal("30"),
                "DAY",
                null
        );
    }

    private AutoParticipantProfileConfigRequest requestWithNestedFundingPolicy() {
        AutoParticipantProfileConfigRequest base = validRequest();
        return new AutoParticipantProfileConfigRequest(
                base.newsWeight(),
                base.momentumWeight(),
                base.contrarianWeight(),
                base.lossAversionWeight(),
                base.herdingWeight(),
                base.marketMakingWeight(),
                base.overconfidenceWeight(),
                base.noiseWeight(),
                base.panicSellWeight(),
                base.dipBuyWeight(),
                base.orderMultiplier(),
                base.decisionFrequencyMultiplier(),
                base.ordersPerDecisionMultiplier(),
                base.aggressionMultiplier(),
                base.pricePressureSensitivity(),
                base.orderTtlMultiplier(),
                base.quantityMultiplier(),
                base.holdingPatienceWeight(),
                base.deepLossHoldWeight(),
                base.profitTakingWeight(),
                base.pricingMode(),
                base.exitMode(),
                base.inventoryMode(),
                new BigDecimal("9000000.00"),
                new BigDecimal("9.0000"),
                "HOUR",
                null,
                new AutoParticipantFundingPolicyRequest(
                        new BigDecimal("7000000.00"),
                        new BigDecimal("2.0000"),
                        "DAY",
                        null
                )
        );
    }

    private AutoParticipantProfileConfigRequest withBehaviorModelVersion(
            AutoParticipantProfileConfigRequest base,
            String behaviorModelVersion
    ) {
        return new AutoParticipantProfileConfigRequest(
                base.newsWeight(),
                base.momentumWeight(),
                base.contrarianWeight(),
                base.lossAversionWeight(),
                base.herdingWeight(),
                base.marketMakingWeight(),
                base.overconfidenceWeight(),
                base.noiseWeight(),
                base.panicSellWeight(),
                base.dipBuyWeight(),
                base.orderMultiplier(),
                base.decisionFrequencyMultiplier(),
                base.ordersPerDecisionMultiplier(),
                base.aggressionMultiplier(),
                base.pricePressureSensitivity(),
                base.orderTtlMultiplier(),
                base.quantityMultiplier(),
                base.holdingPatienceWeight(),
                base.deepLossHoldWeight(),
                base.profitTakingWeight(),
                base.pricingMode(),
                base.exitMode(),
                base.inventoryMode(),
                base.recurringDepositAmount(),
                base.recurringDepositIntervalValue(),
                base.recurringDepositIntervalUnit(),
                base.recurringDepositIntervalDays(),
                base.fundingPolicy(),
                behaviorModelVersion
        );
    }
}
