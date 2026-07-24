package stock.back.service.market.vo;

import java.math.BigDecimal;

public record AutoParticipantRequest(
        String displayName,
        Boolean enabled,
        String profileType,
        BigDecimal recurringCashAmount,
        BigDecimal recurringCashIntervalValue,
        String recurringCashIntervalUnit,
        String behaviorSeed,
        Boolean createAccount,
        BigDecimal initialCashAmount
) {
    public AutoParticipantRequest(String displayName, Boolean enabled, String profileType) {
        this(displayName, enabled, profileType, null, null, null, null, null, null);
    }

    public AutoParticipantRequest(
            String displayName,
            Boolean enabled,
            String profileType,
            BigDecimal recurringCashAmount,
            BigDecimal recurringCashIntervalValue,
            String recurringCashIntervalUnit,
            Boolean createAccount,
            BigDecimal initialCashAmount
    ) {
        this(
                displayName, enabled, profileType,
                recurringCashAmount, recurringCashIntervalValue, recurringCashIntervalUnit,
                null,
                createAccount, initialCashAmount
        );
    }
}
