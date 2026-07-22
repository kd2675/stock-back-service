package stock.back.service.market.vo;

import stock.back.service.database.entity.StockCorporateActionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CashDividendGuidanceResponse(
        String symbol,
        BigDecimal referencePrice,
        String referencePriceBasis,
        long issuedShares,
        long tradableShares,
        Long recentHoldingQuantity,
        Long holdingReferenceCloseRunId,
        LocalDate holdingReferenceBusinessDate,
        long completedDividendCount,
        List<DividendHistory> history
) {

    public record DividendHistory(
            long actionId,
            StockCorporateActionStatus status,
            BigDecimal originalDividendPerShare,
            BigDecimal splitAdjustedDividendPerShare,
            BigDecimal basePrice,
            BigDecimal dividendYield,
            BigDecimal actualPaidCash,
            long eligibleShareQuantity,
            LocalDate exRightsDate,
            LocalDate paymentDate
    ) {
    }
}
