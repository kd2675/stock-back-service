package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RankingResponse(
        int rank,
        String userKey,
        String displayName,
        BigDecimal totalAsset,
        BigDecimal returnRate,
        LocalDate snapshotDate
) {
}
