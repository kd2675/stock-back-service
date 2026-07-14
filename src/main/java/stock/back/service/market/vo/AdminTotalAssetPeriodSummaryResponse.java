package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AdminTotalAssetPeriodSummaryResponse(
        LocalDate rangeStart,
        LocalDate rangeEnd,
        BigDecimal startTotalAsset,
        BigDecimal endTotalAsset,
        BigDecimal changeAmount,
        BigDecimal changeRate,
        BigDecimal averageTotalAsset,
        BigDecimal highestTotalAsset,
        BigDecimal lowestTotalAsset
) {
}
