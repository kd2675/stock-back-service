package stock.back.service.market.vo;

import java.math.BigDecimal;

public record InstitutionScaledPresetRequest(
        BigDecimal institutionAumRateOfMarketCap,
        String changeReason
) {
}
