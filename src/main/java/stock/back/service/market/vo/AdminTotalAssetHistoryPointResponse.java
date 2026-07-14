package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AdminTotalAssetHistoryPointResponse(
        LocalDate snapshotDate,
        long accountCount,
        BigDecimal totalAsset,
        BigDecimal cashBalance,
        BigDecimal marketValue,
        BigDecimal reservedCash,
        BigDecimal changeAmount,
        BigDecimal changeRate
) {
}
