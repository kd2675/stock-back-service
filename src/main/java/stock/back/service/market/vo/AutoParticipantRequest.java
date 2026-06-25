package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AutoParticipantRequest(
        String displayName,
        Boolean enabled,
        String profileType,
        BigDecimal recurringCashAmount,
        BigDecimal recurringCashIntervalValue,
        String recurringCashIntervalUnit
) {
    public AutoParticipantRequest(String displayName, Boolean enabled, String profileType) {
        this(displayName, enabled, profileType, null, null, null);
    }
}
