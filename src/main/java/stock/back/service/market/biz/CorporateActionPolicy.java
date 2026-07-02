package stock.back.service.market.biz;

import stock.back.service.common.exception.StockException;

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

    static void requirePaidInCapitalIncreaseDates(LocalDate exRightsDate, LocalDate paymentDate, LocalDate listingDate) {
        if (exRightsDate == null || paymentDate == null || listingDate == null) {
            throw StockException.badRequest("Paid-in capital increase requires ex-rights, payment, and listing dates");
        }
        if (paymentDate.isBefore(exRightsDate) || listingDate.isBefore(paymentDate)) {
            throw StockException.badRequest("Paid-in capital increase dates must be ordered by ex-rights, payment, listing");
        }
    }

    static void requireFreeShareDistributionDates(LocalDate exRightsDate, LocalDate listingDate) {
        if (exRightsDate == null || listingDate == null) {
            throw StockException.badRequest("Free share distribution requires ex-rights and listing dates");
        }
        if (listingDate.isBefore(exRightsDate)) {
            throw StockException.badRequest("Free share distribution listing date must be on or after ex-rights date");
        }
    }

    static void requireCashDividendDates(LocalDate exRightsDate, LocalDate paymentDate) {
        if (exRightsDate == null || paymentDate == null) {
            throw StockException.badRequest("Cash dividend requires ex-dividend and payment dates");
        }
        if (paymentDate.isBefore(exRightsDate)) {
            throw StockException.badRequest("Cash dividend payment date must be on or after ex-dividend date");
        }
    }

    static LocalDate requireAdditionalIssueListingDate(LocalDate listingDate) {
        if (listingDate == null) {
            throw StockException.badRequest("Additional issue requires a listing date");
        }
        return listingDate;
    }

    static LocalDate requireStockSplitListingDate(LocalDate listingDate) {
        if (listingDate == null) {
            throw StockException.badRequest("Stock split requires an effective date");
        }
        return listingDate;
    }

    static LocalDate requireDelistingDate(LocalDate delistingDate) {
        if (delistingDate == null) {
            throw StockException.badRequest("Delisting requires a delisting date");
        }
        return delistingDate;
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
