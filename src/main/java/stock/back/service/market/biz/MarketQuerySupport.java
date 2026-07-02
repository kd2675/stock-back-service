package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

final class MarketQuerySupport {

    private MarketQuerySupport() {
    }

    static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static LocalDateTime toDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
