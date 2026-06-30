package stock.back.service.market.biz;

import stock.back.service.common.exception.StockException;

import java.math.BigDecimal;

final class NumericRangePolicy {

    private NumericRangePolicy() {
    }

    static BigDecimal requireBigDecimal(BigDecimal value, String fieldName, BigDecimal min, BigDecimal max) {
        if (value == null) {
            throw StockException.badRequest(fieldName + " is required");
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw StockException.badRequest(fieldName + " must be between " + min + " and " + max);
        }
        return value;
    }
}
