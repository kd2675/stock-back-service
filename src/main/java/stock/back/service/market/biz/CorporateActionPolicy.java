package stock.back.service.market.biz;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

final class CorporateActionPolicy {

    private CorporateActionPolicy() {
    }

    static String requireSymbol(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        return normalizedSymbol;
    }

    static long requirePositiveShareQuantity(Long shareQuantity) {
        long shares = shareQuantity == null ? 0L : shareQuantity;
        if (shares <= 0) {
            throw StockException.badRequest("Share quantity must be positive");
        }
        return shares;
    }

    static BigDecimal requirePositiveIssuePrice(BigDecimal issuePrice) {
        if (issuePrice == null || issuePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Share issue requires a positive issue price");
        }
        return issuePrice;
    }

    static BigDecimal requirePositiveDividendAmount(BigDecimal dividendAmount) {
        if (dividendAmount == null || dividendAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Cash dividend amount must be positive");
        }
        return dividendAmount;
    }

    static void requirePaidInCapitalIncreaseDates(
            StockCapitalIncreaseOfferingType offeringType,
            LocalDate exRightsDate,
            LocalDate subscriptionStartDate,
            LocalDate subscriptionEndDate,
            LocalDate paymentDate,
            LocalDate listingDate,
            LocalDate currentSimulationDate
    ) {
        StockCapitalIncreaseOfferingType normalizedOfferingType = offeringType == null
                ? StockCapitalIncreaseOfferingType.defaultType()
                : offeringType;
        if (paymentDate == null || listingDate == null || subscriptionStartDate == null || subscriptionEndDate == null) {
            throw StockException.badRequest("Paid-in capital increase requires subscription, payment, and listing dates");
        }
        if (normalizedOfferingType == StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION && exRightsDate == null) {
            throw StockException.badRequest("Shareholder allocation requires an ex-rights date");
        }
        if (normalizedOfferingType == StockCapitalIncreaseOfferingType.PUBLIC_OFFERING && exRightsDate != null) {
            throw StockException.badRequest("Public offering does not use an ex-rights date");
        }
        if (exRightsDate != null) {
            requireNotBeforeCurrentSimulationDate("Paid-in capital increase ex-rights date", exRightsDate, currentSimulationDate);
        }
        requireNotBeforeCurrentSimulationDate("Paid-in capital increase subscription start date", subscriptionStartDate, currentSimulationDate);
        requireNotBeforeCurrentSimulationDate("Paid-in capital increase subscription end date", subscriptionEndDate, currentSimulationDate);
        requireNotBeforeCurrentSimulationDate("Paid-in capital increase payment date", paymentDate, currentSimulationDate);
        requireNotBeforeCurrentSimulationDate("Paid-in capital increase listing date", listingDate, currentSimulationDate);
        if (subscriptionEndDate.isBefore(subscriptionStartDate)) {
            throw StockException.badRequest("Paid-in capital increase subscription end date must not be before subscription start date");
        }
        if (normalizedOfferingType == StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION
                && !subscriptionStartDate.isAfter(exRightsDate)) {
            throw StockException.badRequest("Shareholder allocation subscription must start after ex-rights date");
        }
        if (!paymentDate.isAfter(subscriptionEndDate) || !listingDate.isAfter(paymentDate)) {
            throw StockException.badRequest("Paid-in capital increase dates must be ordered by subscription, payment, listing");
        }
    }

    static LocalDate defaultPaidInSubscriptionStartDate(
            StockCapitalIncreaseOfferingType offeringType,
            LocalDate exRightsDate,
            LocalDate currentSimulationDate
    ) {
        StockCapitalIncreaseOfferingType normalizedOfferingType = offeringType == null
                ? StockCapitalIncreaseOfferingType.defaultType()
                : offeringType;
        if (normalizedOfferingType == StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION && exRightsDate != null) {
            return exRightsDate.plusDays(1);
        }
        return currentSimulationDate;
    }

    static void requireFreeShareDistributionDates(LocalDate exRightsDate, LocalDate listingDate, LocalDate currentSimulationDate) {
        if (exRightsDate == null || listingDate == null) {
            throw StockException.badRequest("Free share distribution requires ex-rights and listing dates");
        }
        requireNotBeforeCurrentSimulationDate("Free share distribution ex-rights date", exRightsDate, currentSimulationDate);
        requireNotBeforeCurrentSimulationDate("Free share distribution listing date", listingDate, currentSimulationDate);
        if (!listingDate.isAfter(exRightsDate)) {
            throw StockException.badRequest("Free share distribution listing date must be after ex-rights date");
        }
    }

    static void requireCashDividendDates(LocalDate exRightsDate, LocalDate paymentDate, LocalDate currentSimulationDate) {
        if (exRightsDate == null || paymentDate == null) {
            throw StockException.badRequest("Cash dividend requires ex-dividend and payment dates");
        }
        requireNotBeforeCurrentSimulationDate("Cash dividend ex-dividend date", exRightsDate, currentSimulationDate);
        requireNotBeforeCurrentSimulationDate("Cash dividend payment date", paymentDate, currentSimulationDate);
        if (!paymentDate.isAfter(exRightsDate)) {
            throw StockException.badRequest("Cash dividend payment date must be after ex-dividend date");
        }
    }

    static LocalDate requireStockSplitListingDate(LocalDate listingDate, LocalDate currentSimulationDate) {
        if (listingDate == null) {
            throw StockException.badRequest("Stock split requires an effective date");
        }
        requireNotBeforeCurrentSimulationDate("Stock split effective date", listingDate, currentSimulationDate);
        return listingDate;
    }

    static LocalDate requireDelistingDate(LocalDate delistingDate, LocalDate currentSimulationDate) {
        if (delistingDate == null) {
            throw StockException.badRequest("Delisting requires a delisting date");
        }
        requireNotBeforeCurrentSimulationDate("Delisting date", delistingDate, currentSimulationDate);
        return delistingDate;
    }

    private static void requireNotBeforeCurrentSimulationDate(
            String fieldName,
            LocalDate date,
            LocalDate currentSimulationDate
    ) {
        if (currentSimulationDate != null && date.isBefore(currentSimulationDate)) {
            throw StockException.badRequest(fieldName + " must not be before current simulation date");
        }
    }

    static void requireSupportedStockSplitRatio(Integer splitFrom, Integer splitTo) {
        if (splitFrom == null || splitTo == null || splitFrom <= 0 || splitTo <= 0 || splitTo <= splitFrom) {
            throw StockException.badRequest("Stock split ratio must be positive and greater than 1:1");
        }
        if (splitTo % splitFrom != 0) {
            throw StockException.badRequest("Only integer share split ratios are supported");
        }
    }

    static String normalizeNullableDescription(String value) {
        String description = MarketTextNormalizer.text(value);
        if (description.isBlank()) {
            return null;
        }
        if (description.length() > 255) {
            throw StockException.badRequest("Description must be 255 characters or less");
        }
        return description;
    }

    static BigDecimal calculateTheoreticalExRightsPrice(
            long existingShares,
            BigDecimal basePrice,
            long newShares,
            BigDecimal issuePrice
    ) {
        BigDecimal existingValue = basePrice.multiply(BigDecimal.valueOf(existingShares));
        BigDecimal issueValue = issuePrice.multiply(BigDecimal.valueOf(newShares));
        return existingValue.add(issueValue)
                .divide(BigDecimal.valueOf(existingShares + newShares), 2, RoundingMode.HALF_UP);
    }

    static BigDecimal calculateTheoreticalFreeSharePrice(
            long existingShares,
            BigDecimal basePrice,
            long newShares
    ) {
        BigDecimal existingValue = basePrice.multiply(BigDecimal.valueOf(existingShares));
        return existingValue.divide(BigDecimal.valueOf(existingShares + newShares), 2, RoundingMode.HALF_UP);
    }
}
