package stock.back.service.market.vo;

import java.math.BigDecimal;

public record InstitutionPortfolioCashAdjustmentRequest(
        String adjustmentType,
        BigDecimal amount
) {
}
