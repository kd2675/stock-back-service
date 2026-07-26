package stock.back.service.market.vo;

import java.math.BigDecimal;

public record LiquidityProviderProvisionRequest(
        Long sourceAccountId,
        BigDecimal referenceDailyVolumeRate,
        BigDecimal seedInventoryRate,
        BigDecimal initialCashToInventoryValue,
        String changeReason
) {
}
