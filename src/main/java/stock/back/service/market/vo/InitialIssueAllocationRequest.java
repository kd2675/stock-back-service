package stock.back.service.market.vo;

import java.math.BigDecimal;

public record InitialIssueAllocationRequest(
        String mode,
        BigDecimal tradableShareRate
) {
}
