package stock.back.service.market.biz;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.RecurringCashIntervalUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class RecurringCashPolicy {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
    private static final BigDecimal MAX_INTERVAL_VALUE = new BigDecimal("1000");

    private RecurringCashPolicy() {
    }

    static BigDecimal normalizeAmount(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(MAX_AMOUNT) > 0) {
            throw StockException.badRequest("Recurring cash amount must be between 0 and 1000000000000");
        }
        return value;
    }

    static BigDecimal normalizeIntervalValue(BigDecimal value, BigDecimal amount) {
        if (amount == null) {
            return value == null ? null : requireIntervalValue(value);
        }
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return value == null ? BigDecimal.ZERO : requireIntervalValue(value);
        }
        if (value == null) {
            throw StockException.badRequest("Recurring cash interval value is required when recurring cash amount is positive");
        }
        BigDecimal interval = requireIntervalValue(value);
        if (interval.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Recurring cash interval value must be greater than 0 when recurring cash amount is positive");
        }
        return interval;
    }

    static RecurringCashIntervalUnit normalizeIntervalUnit(String value, BigDecimal amount) {
        String normalized = MarketTextNormalizer.text(value);
        if (amount == null && normalized.isBlank()) {
            return null;
        }
        if (amount != null && amount.compareTo(BigDecimal.ZERO) == 0 && normalized.isBlank()) {
            return null;
        }
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0 && normalized.isBlank()) {
            throw StockException.badRequest("Recurring cash interval unit is required when recurring cash amount is positive");
        }
        try {
            return RecurringCashIntervalUnit.parseOrDefault(normalized);
        } catch (IllegalArgumentException exception) {
            throw StockException.badRequest("Unknown recurring cash interval unit");
        }
    }

    static Integer intervalDays(BigDecimal value, RecurringCashIntervalUnit unit) {
        if (!RecurringCashIntervalUnit.DAY.equals(unit)) {
            return 1;
        }
        return value.setScale(0, RoundingMode.CEILING).intValue();
    }

    private static BigDecimal requireIntervalValue(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(MAX_INTERVAL_VALUE) > 0) {
            throw StockException.badRequest("Recurring cash interval value must be between 0 and 1000");
        }
        return value;
    }

}
