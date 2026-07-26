package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.util.List;

public record InstitutionPortfolioCreateRequest(
        String portfolioCode,
        String displayName,
        String investmentStyle,
        BigDecimal institutionAumRateOfMarketCap,
        List<String> symbols,
        String changeReason
) {
}
