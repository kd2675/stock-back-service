package stock.back.service.trading.vo;

import java.math.BigDecimal;

public record AccountCashAdjustmentRequest(
        String adjustmentType,
        BigDecimal amount
) {
}
