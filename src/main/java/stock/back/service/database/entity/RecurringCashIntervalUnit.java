package stock.back.service.database.entity;

import java.util.Locale;

public enum RecurringCashIntervalUnit {
    SECOND,
    MINUTE,
    HOUR,
    DAY,
    MONTH,
    YEAR;

    public static RecurringCashIntervalUnit parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        return RecurringCashIntervalUnit.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
