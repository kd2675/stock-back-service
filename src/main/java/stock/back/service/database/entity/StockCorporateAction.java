package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "stock_corporate_action")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCorporateAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40)
    private StockCorporateActionType actionType;

    @Column(name = "share_quantity")
    private Long shareQuantity;

    @Column(name = "issue_price", precision = 19, scale = 2)
    private BigDecimal issuePrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StockCorporateActionStatus status;

    @Column(name = "base_price", precision = 19, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "theoretical_ex_rights_price", precision = 19, scale = 2)
    private BigDecimal theoreticalExRightsPrice;

    @Column(name = "dividend_amount", precision = 19, scale = 2)
    private BigDecimal dividendAmount;

    @Column(name = "ex_rights_date")
    private LocalDate exRightsDate;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "listing_date")
    private LocalDate listingDate;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "listed_at")
    private LocalDateTime listedAt;

    @Column(name = "split_from")
    private Integer splitFrom;

    @Column(name = "split_to")
    private Integer splitTo;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static StockCorporateAction initialIssue(String symbol, long shares, BigDecimal issuePrice) {
        StockCorporateAction action = create(symbol, StockCorporateActionType.INITIAL_ISSUE, shares, issuePrice, null, null, "Initial issue");
        action.status = StockCorporateActionStatus.LISTED;
        action.listedAt = action.createdAt;
        return action;
    }

    public static StockCorporateAction additionalIssue(
            String symbol,
            long shares,
            BigDecimal issuePrice,
            LocalDate listingDate,
            String description
    ) {
        StockCorporateAction action = create(symbol, StockCorporateActionType.ADDITIONAL_ISSUE, shares, issuePrice, null, null, description);
        action.status = StockCorporateActionStatus.ANNOUNCED;
        action.listingDate = listingDate;
        return action;
    }

    public static StockCorporateAction paidInCapitalIncrease(
            String symbol,
            long shares,
            BigDecimal issuePrice,
            BigDecimal basePrice,
            BigDecimal theoreticalExRightsPrice,
            LocalDate exRightsDate,
            LocalDate paymentDate,
            LocalDate listingDate,
            String description
    ) {
        StockCorporateAction action = create(symbol, StockCorporateActionType.PAID_IN_CAPITAL_INCREASE, shares, issuePrice, null, null, description);
        action.status = StockCorporateActionStatus.ANNOUNCED;
        action.basePrice = basePrice;
        action.theoreticalExRightsPrice = theoreticalExRightsPrice;
        action.exRightsDate = exRightsDate;
        action.paymentDate = paymentDate;
        action.listingDate = listingDate;
        return action;
    }

    public static StockCorporateAction cashDividend(
            String symbol,
            BigDecimal dividendAmount,
            BigDecimal basePrice,
            BigDecimal theoreticalExRightsPrice,
            LocalDate exRightsDate,
            LocalDate paymentDate,
            String description
    ) {
        StockCorporateAction action = create(symbol, StockCorporateActionType.CASH_DIVIDEND, null, null, null, null, description);
        action.status = StockCorporateActionStatus.ANNOUNCED;
        action.dividendAmount = dividendAmount;
        action.basePrice = basePrice;
        action.theoreticalExRightsPrice = theoreticalExRightsPrice;
        action.exRightsDate = exRightsDate;
        action.paymentDate = paymentDate;
        return action;
    }

    public static StockCorporateAction bonusIssue(
            String symbol,
            long shares,
            BigDecimal basePrice,
            BigDecimal theoreticalExRightsPrice,
            LocalDate exRightsDate,
            LocalDate listingDate,
            String description
    ) {
        StockCorporateAction action = create(symbol, StockCorporateActionType.BONUS_ISSUE, shares, null, null, null, description);
        action.status = StockCorporateActionStatus.ANNOUNCED;
        action.basePrice = basePrice;
        action.theoreticalExRightsPrice = theoreticalExRightsPrice;
        action.exRightsDate = exRightsDate;
        action.listingDate = listingDate;
        return action;
    }

    public static StockCorporateAction stockDividend(
            String symbol,
            long shares,
            BigDecimal basePrice,
            BigDecimal theoreticalExRightsPrice,
            LocalDate exRightsDate,
            LocalDate listingDate,
            String description
    ) {
        StockCorporateAction action = create(symbol, StockCorporateActionType.STOCK_DIVIDEND, shares, null, null, null, description);
        action.status = StockCorporateActionStatus.ANNOUNCED;
        action.basePrice = basePrice;
        action.theoreticalExRightsPrice = theoreticalExRightsPrice;
        action.exRightsDate = exRightsDate;
        action.listingDate = listingDate;
        return action;
    }

    public static StockCorporateAction stockSplit(String symbol, int splitFrom, int splitTo, LocalDate listingDate, String description) {
        StockCorporateAction action = create(symbol, StockCorporateActionType.STOCK_SPLIT, null, null, splitFrom, splitTo, description);
        action.status = StockCorporateActionStatus.ANNOUNCED;
        action.listingDate = listingDate;
        return action;
    }

    private static StockCorporateAction create(
            String symbol,
            StockCorporateActionType actionType,
            Long shareQuantity,
            BigDecimal issuePrice,
            Integer splitFrom,
            Integer splitTo,
            String description
    ) {
        StockCorporateAction action = new StockCorporateAction();
        action.symbol = symbol;
        action.actionType = actionType;
        action.shareQuantity = shareQuantity;
        action.issuePrice = issuePrice;
        action.status = StockCorporateActionStatus.ANNOUNCED;
        action.splitFrom = splitFrom;
        action.splitTo = splitTo;
        action.description = description;
        action.createdAt = LocalDateTime.now();
        return action;
    }
}
