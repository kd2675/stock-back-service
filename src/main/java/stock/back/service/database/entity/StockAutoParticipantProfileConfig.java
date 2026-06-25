package stock.back.service.database.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "stock_auto_participant_profile_config")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAutoParticipantProfileConfig {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "profile_type", nullable = false, length = 40)
    private AutoParticipantProfileType profileType;

    @Column(name = "news_weight", precision = 8, scale = 4)
    private BigDecimal newsWeight;

    @Column(name = "momentum_weight", precision = 8, scale = 4)
    private BigDecimal momentumWeight;

    @Column(name = "contrarian_weight", precision = 8, scale = 4)
    private BigDecimal contrarianWeight;

    @Column(name = "loss_aversion_weight", precision = 8, scale = 4)
    private BigDecimal lossAversionWeight;

    @Column(name = "herding_weight", precision = 8, scale = 4)
    private BigDecimal herdingWeight;

    @Column(name = "market_making_weight", precision = 8, scale = 4)
    private BigDecimal marketMakingWeight;

    @Column(name = "overconfidence_weight", precision = 8, scale = 4)
    private BigDecimal overconfidenceWeight;

    @Column(name = "noise_weight", precision = 8, scale = 4)
    private BigDecimal noiseWeight;

    @Column(name = "panic_sell_weight", precision = 8, scale = 4)
    private BigDecimal panicSellWeight;

    @Column(name = "dip_buy_weight", precision = 8, scale = 4)
    private BigDecimal dipBuyWeight;

    @Column(name = "order_multiplier", nullable = false, precision = 8, scale = 4)
    private BigDecimal orderMultiplier;

    @Column(name = "aggression_multiplier", nullable = false, precision = 8, scale = 4)
    private BigDecimal aggressionMultiplier;

    @Column(name = "order_ttl_multiplier", nullable = false, precision = 8, scale = 4)
    private BigDecimal orderTtlMultiplier;

    @Column(name = "quantity_multiplier", nullable = false, precision = 8, scale = 4)
    private BigDecimal quantityMultiplier;

    @Column(name = "holding_patience_weight", nullable = false, precision = 8, scale = 4)
    private BigDecimal holdingPatienceWeight;

    @Column(name = "deep_loss_hold_weight", nullable = false, precision = 8, scale = 4)
    private BigDecimal deepLossHoldWeight;

    @Column(name = "profit_taking_weight", nullable = false, precision = 8, scale = 4)
    private BigDecimal profitTakingWeight;

    @Column(name = "recurring_deposit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal recurringDepositAmount;

    @Column(name = "recurring_deposit_interval_days", nullable = false)
    private Integer recurringDepositIntervalDays;

    @Column(name = "recurring_deposit_interval_value", nullable = false, precision = 12, scale = 4)
    private BigDecimal recurringDepositIntervalValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurring_deposit_interval_unit", nullable = false, length = 20)
    private RecurringCashIntervalUnit recurringDepositIntervalUnit;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StockAutoParticipantProfileConfig create(
            AutoParticipantProfileType profileType,
            BigDecimal newsWeight,
            BigDecimal momentumWeight,
            BigDecimal contrarianWeight,
            BigDecimal lossAversionWeight,
            BigDecimal herdingWeight,
            BigDecimal marketMakingWeight,
            BigDecimal overconfidenceWeight,
            BigDecimal noiseWeight,
            BigDecimal panicSellWeight,
            BigDecimal dipBuyWeight,
            BigDecimal orderMultiplier,
            BigDecimal aggressionMultiplier,
            BigDecimal orderTtlMultiplier,
            BigDecimal quantityMultiplier,
            BigDecimal holdingPatienceWeight,
            BigDecimal deepLossHoldWeight,
            BigDecimal profitTakingWeight,
            BigDecimal recurringDepositAmount,
            Integer recurringDepositIntervalDays
    ) {
        return create(
                profileType,
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                noiseWeight,
                panicSellWeight,
                dipBuyWeight,
                orderMultiplier,
                aggressionMultiplier,
                orderTtlMultiplier,
                quantityMultiplier,
                holdingPatienceWeight,
                deepLossHoldWeight,
                profitTakingWeight,
                recurringDepositAmount,
                BigDecimal.valueOf(recurringDepositIntervalDays == null ? 30 : recurringDepositIntervalDays),
                RecurringCashIntervalUnit.DAY
        );
    }

    public static StockAutoParticipantProfileConfig create(
            AutoParticipantProfileType profileType,
            BigDecimal newsWeight,
            BigDecimal momentumWeight,
            BigDecimal contrarianWeight,
            BigDecimal lossAversionWeight,
            BigDecimal herdingWeight,
            BigDecimal marketMakingWeight,
            BigDecimal overconfidenceWeight,
            BigDecimal noiseWeight,
            BigDecimal panicSellWeight,
            BigDecimal dipBuyWeight,
            BigDecimal orderMultiplier,
            BigDecimal aggressionMultiplier,
            BigDecimal orderTtlMultiplier,
            BigDecimal quantityMultiplier,
            BigDecimal holdingPatienceWeight,
            BigDecimal deepLossHoldWeight,
            BigDecimal profitTakingWeight,
            BigDecimal recurringDepositAmount,
            BigDecimal recurringDepositIntervalValue,
            RecurringCashIntervalUnit recurringDepositIntervalUnit
    ) {
        StockAutoParticipantProfileConfig config = new StockAutoParticipantProfileConfig();
        config.profileType = profileType;
        config.update(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                noiseWeight,
                panicSellWeight,
                dipBuyWeight,
                orderMultiplier,
                aggressionMultiplier,
                orderTtlMultiplier,
                quantityMultiplier,
                holdingPatienceWeight,
                deepLossHoldWeight,
                profitTakingWeight,
                recurringDepositAmount,
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit
        );
        return config;
    }

    public void update(
            BigDecimal newsWeight,
            BigDecimal momentumWeight,
            BigDecimal contrarianWeight,
            BigDecimal lossAversionWeight,
            BigDecimal herdingWeight,
            BigDecimal marketMakingWeight,
            BigDecimal overconfidenceWeight,
            BigDecimal noiseWeight,
            BigDecimal panicSellWeight,
            BigDecimal dipBuyWeight,
            BigDecimal orderMultiplier,
            BigDecimal aggressionMultiplier,
            BigDecimal orderTtlMultiplier,
            BigDecimal quantityMultiplier,
            BigDecimal holdingPatienceWeight,
            BigDecimal deepLossHoldWeight,
            BigDecimal profitTakingWeight,
            BigDecimal recurringDepositAmount,
            Integer recurringDepositIntervalDays
    ) {
        update(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                noiseWeight,
                panicSellWeight,
                dipBuyWeight,
                orderMultiplier,
                aggressionMultiplier,
                orderTtlMultiplier,
                quantityMultiplier,
                holdingPatienceWeight,
                deepLossHoldWeight,
                profitTakingWeight,
                recurringDepositAmount,
                BigDecimal.valueOf(recurringDepositIntervalDays == null ? 30 : recurringDepositIntervalDays),
                RecurringCashIntervalUnit.DAY
        );
    }

    public void update(
            BigDecimal newsWeight,
            BigDecimal momentumWeight,
            BigDecimal contrarianWeight,
            BigDecimal lossAversionWeight,
            BigDecimal herdingWeight,
            BigDecimal marketMakingWeight,
            BigDecimal overconfidenceWeight,
            BigDecimal noiseWeight,
            BigDecimal panicSellWeight,
            BigDecimal dipBuyWeight,
            BigDecimal orderMultiplier,
            BigDecimal aggressionMultiplier,
            BigDecimal orderTtlMultiplier,
            BigDecimal quantityMultiplier,
            BigDecimal holdingPatienceWeight,
            BigDecimal deepLossHoldWeight,
            BigDecimal profitTakingWeight,
            BigDecimal recurringDepositAmount,
            BigDecimal recurringDepositIntervalValue,
            RecurringCashIntervalUnit recurringDepositIntervalUnit
    ) {
        this.newsWeight = newsWeight;
        this.momentumWeight = momentumWeight;
        this.contrarianWeight = contrarianWeight;
        this.lossAversionWeight = lossAversionWeight;
        this.herdingWeight = herdingWeight;
        this.marketMakingWeight = marketMakingWeight;
        this.overconfidenceWeight = overconfidenceWeight;
        this.noiseWeight = noiseWeight;
        this.panicSellWeight = panicSellWeight;
        this.dipBuyWeight = dipBuyWeight;
        this.orderMultiplier = orderMultiplier;
        this.aggressionMultiplier = aggressionMultiplier;
        this.orderTtlMultiplier = orderTtlMultiplier;
        this.quantityMultiplier = quantityMultiplier;
        this.holdingPatienceWeight = holdingPatienceWeight;
        this.deepLossHoldWeight = deepLossHoldWeight;
        this.profitTakingWeight = profitTakingWeight;
        this.recurringDepositAmount = recurringDepositAmount;
        this.recurringDepositIntervalValue = recurringDepositIntervalValue;
        this.recurringDepositIntervalUnit = recurringDepositIntervalUnit;
        this.recurringDepositIntervalDays = RecurringCashIntervalUnit.DAY.equals(recurringDepositIntervalUnit)
                ? recurringDepositIntervalValue.setScale(0, java.math.RoundingMode.CEILING).intValue()
                : 1;
        this.updatedAt = LocalDateTime.now();
    }
}
