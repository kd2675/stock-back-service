package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AutoParticipantResponse(
        String userKey,
        String displayName,
        boolean enabled,
        String profileType,
        String behaviorModelVersion,
        String behaviorSeed,
        BigDecimal recurringCashAmount,
        BigDecimal recurringCashIntervalValue,
        String recurringCashIntervalUnit,
        Long accountId,
        String accountStatus,
        BigDecimal cashBalance,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime withdrawnAt,
        BigDecimal paydayAvailableBudget,
        BigDecimal dividendAvailableBudget,
        BigDecimal fundingReservedAmount,
        BigDecimal fundingSpentAmount,
        long activeFundingBudgetCount,
        long trackedPositionCount,
        BigDecimal averageHoldingTradingDays,
        long averageDownRoundCount,
        BigDecimal withdrawalReturnedCashAmount,
        long withdrawalReturnedShareQuantity,
        int withdrawalReturnedSymbolCount,
        boolean accountClosedOnWithdrawal
) {
    public AutoParticipantResponse(
            String userKey,
            String displayName,
            boolean enabled,
            String profileType,
            String behaviorModelVersion,
            String behaviorSeed,
            BigDecimal recurringCashAmount,
            BigDecimal recurringCashIntervalValue,
            String recurringCashIntervalUnit,
            Long accountId,
            String accountStatus,
            BigDecimal cashBalance,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime withdrawnAt
    ) {
        this(
                userKey, displayName, enabled, profileType,
                behaviorModelVersion, behaviorSeed,
                recurringCashAmount, recurringCashIntervalValue, recurringCashIntervalUnit,
                accountId, accountStatus, cashBalance, createdAt, updatedAt, withdrawnAt,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0L, 0L, BigDecimal.ZERO, 0L,
                BigDecimal.ZERO, 0L, 0, false
        );
    }

    public AutoParticipantResponse(
            String userKey,
            String displayName,
            boolean enabled,
            String profileType,
            BigDecimal recurringCashAmount,
            BigDecimal recurringCashIntervalValue,
            String recurringCashIntervalUnit,
            Long accountId,
            String accountStatus,
            BigDecimal cashBalance,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime withdrawnAt
    ) {
        this(
                userKey, displayName, enabled, profileType,
                "V1", null,
                recurringCashAmount, recurringCashIntervalValue, recurringCashIntervalUnit,
                accountId, accountStatus, cashBalance, createdAt, updatedAt, withdrawnAt,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0L, 0L, BigDecimal.ZERO, 0L,
                BigDecimal.ZERO, 0L, 0, false
        );
    }

    public AutoParticipantResponse withWithdrawalSettlement(
            BigDecimal returnedCashAmount,
            long returnedShareQuantity,
            int returnedSymbolCount,
            boolean accountClosed
    ) {
        return new AutoParticipantResponse(
                userKey, displayName, enabled, profileType,
                behaviorModelVersion, behaviorSeed,
                recurringCashAmount, recurringCashIntervalValue, recurringCashIntervalUnit,
                accountId, accountStatus, cashBalance, createdAt, updatedAt, withdrawnAt,
                paydayAvailableBudget, dividendAvailableBudget, fundingReservedAmount, fundingSpentAmount,
                activeFundingBudgetCount, trackedPositionCount, averageHoldingTradingDays, averageDownRoundCount,
                returnedCashAmount == null ? BigDecimal.ZERO : returnedCashAmount,
                returnedShareQuantity, returnedSymbolCount, accountClosed
        );
    }
}
