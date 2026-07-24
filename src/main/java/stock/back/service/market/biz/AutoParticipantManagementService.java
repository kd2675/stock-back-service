package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.RecurringCashIntervalUnit;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountParticipantCategory;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockAutoParticipantProfileConfigRepository;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AutoParticipantManagementService {

    private static final String AUTO_PARTICIPANT_GENERATE_CREATED_BY = "AUTO_PARTICIPANT_GENERATE";

    private final StockAutoParticipantRepository stockAutoParticipantRepository;
    private final StockAutoParticipantProfileConfigRepository stockAutoParticipantProfileConfigRepository;
    private final StockAccountRepository stockAccountRepository;
    private final StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private final AutoParticipantStrategyTransitionService strategyTransitionService;
    private final AutoParticipantWithdrawalSettlementService withdrawalSettlementService;
    private final SimulationClockService simulationClockService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    @Transactional
    public AutoParticipantResponse upsertAutoParticipant(String userKey, AutoParticipantRequest request) {
        return upsertAutoParticipant(userKey, request, null);
    }

    @Transactional
    public AutoParticipantResponse upsertAutoParticipant(String userKey, AutoParticipantRequest request, String adminUserKey) {
        String normalizedUserKey = MarketTextNormalizer.text(userKey);
        if (normalizedUserKey.isBlank()) {
            throw StockException.badRequest("Auto participant user key is required");
        }
        if (normalizedUserKey.length() > 64) {
            throw StockException.badRequest("Auto participant user key must be 64 characters or less");
        }
        String displayName = MarketTextNormalizer.text(request == null ? null : request.displayName());
        if (displayName.isBlank()) {
            throw StockException.badRequest("Auto participant display name is required");
        }
        if (displayName.length() > 80) {
            throw StockException.badRequest("Auto participant display name must be 80 characters or less");
        }
        AutoParticipantProfileType profileType = parseAutoParticipantProfileType(request == null ? null : request.profileType());
        Long behaviorSeed = normalizeBehaviorSeed(request == null ? null : request.behaviorSeed());
        BigDecimal recurringCashAmount = RecurringCashPolicy.normalizeAmount(request == null ? null : request.recurringCashAmount());
        BigDecimal recurringCashIntervalValue = RecurringCashPolicy.normalizeIntervalValue(
                request == null ? null : request.recurringCashIntervalValue(),
                recurringCashAmount
        );
        RecurringCashIntervalUnit recurringCashIntervalUnit = RecurringCashPolicy.normalizeIntervalUnit(
                request == null ? null : request.recurringCashIntervalUnit(),
                recurringCashAmount
        );
        var existingParticipant = stockAutoParticipantRepository.findById(normalizedUserKey);
        existingParticipant.ifPresent(existing -> requireNotWithdrawn(existing, normalizedUserKey));
        retirePreviousStrategyIfChanged(
                normalizedUserKey,
                existingParticipant.orElse(null),
                request,
                profileType
        );
        StockAutoParticipant participant = existingParticipant
                .map(existing -> {
                    existing.update(
                            displayName,
                            request == null ? null : request.enabled(),
                            profileType,
                            behaviorSeed,
                            recurringCashAmount,
                            recurringCashIntervalValue,
                            recurringCashIntervalUnit
                    );
                    return existing;
                })
                .orElseGet(() -> {
                    StockAutoParticipant created = StockAutoParticipant.create(
                            normalizedUserKey,
                            displayName,
                            request == null || request.enabled() == null || request.enabled(),
                            profileType,
                            recurringCashAmount,
                            recurringCashIntervalValue,
                            recurringCashIntervalUnit
                    );
                    created.update(
                            displayName,
                            request == null ? null : request.enabled(),
                            profileType,
                            behaviorSeed,
                            recurringCashAmount,
                            recurringCashIntervalValue,
                            recurringCashIntervalUnit
                    );
                    return created;
                });
        StockAutoParticipant savedParticipant = stockAutoParticipantRepository.save(participant);
        StockAccount account = ensureAccountAndInitialCash(normalizedUserKey, request, adminUserKey);
        return toAutoParticipantResponse(
                savedParticipant,
                account == null ? stockAccountRepository.findByUserKey(savedParticipant.getUserKey()).orElse(null) : account
        );
    }

    @Transactional
    public AutoParticipantResponse withdrawAutoParticipant(String userKey) {
        return withdrawAutoParticipant(userKey, null);
    }

    @Transactional
    public AutoParticipantResponse withdrawAutoParticipant(String userKey, String adminUserKey) {
        String normalizedUserKey = MarketTextNormalizer.text(userKey);
        if (normalizedUserKey.isBlank()) {
            throw StockException.badRequest("Auto participant user key is required");
        }
        marketLedgerFreezeGuard.acquireMutationPermit("auto-participant withdrawal");
        StockAutoParticipant participant = stockAutoParticipantRepository.findByUserKeyForUpdate(normalizedUserKey)
                .orElseThrow(() -> StockException.notFound("Unknown auto participant: " + normalizedUserKey));
        AutoParticipantWithdrawalSettlementService.WithdrawalSettlement settlement =
                withdrawalSettlementService.findCompletedSettlement(normalizedUserKey)
                        .orElseGet(() -> withdrawalSettlementService.settle(
                                normalizedUserKey,
                                adminUserKey,
                                simulationClockService.currentMarketDateTime()
                        ));
        participant.withdraw();
        StockAutoParticipant savedParticipant = stockAutoParticipantRepository.save(participant);
        return toAutoParticipantResponse(savedParticipant).withWithdrawalSettlement(
                settlement.returnedCashAmount(),
                settlement.returnedShareQuantity(),
                settlement.returnedSymbolCount(),
                settlement.accountClosed()
        );
    }

    private void requireNotWithdrawn(StockAutoParticipant participant, String userKey) {
        if (participant.getWithdrawnAt() != null) {
            throw StockException.conflict(
                    "Withdrawn auto participant cannot be reactivated; register a new user key: " + userKey
            );
        }
    }

    private void retireCurrentStrategy(String userKey) {
        stockAccountRepository.findByUserKeyAndStatusForUpdate(userKey, StockAccountStatus.ACTIVE)
                .ifPresent(account -> strategyTransitionService.retireOpenOrdersAndFundingBudgets(
                        account,
                        simulationClockService.currentMarketDateTime()
                ));
    }

    private void retirePreviousStrategyIfChanged(
            String userKey,
            StockAutoParticipant existing,
            AutoParticipantRequest request,
            AutoParticipantProfileType nextProfileType
    ) {
        if (existing == null || !strategyChanged(
                existing,
                request,
                nextProfileType
        )) {
            return;
        }
        marketLedgerFreezeGuard.acquireMutationPermit("auto-participant strategy transition");
        retireCurrentStrategy(userKey);
    }

    private boolean strategyChanged(
            StockAutoParticipant existing,
            AutoParticipantRequest request,
            AutoParticipantProfileType nextProfileType
    ) {
        boolean nextEnabled = request == null || request.enabled() == null
                ? Boolean.TRUE.equals(existing.getEnabled())
                : request.enabled();
        return !nextEnabled
                || existing.getProfileType() != nextProfileType;
    }

    private StockAccount ensureAccountAndInitialCash(String userKey, AutoParticipantRequest request, String adminUserKey) {
        BigDecimal initialCashAmount = normalizeInitialCashAmount(request == null ? null : request.initialCashAmount());
        boolean shouldCreateAccount = Boolean.TRUE.equals(request == null ? null : request.createAccount())
                || initialCashAmount.compareTo(BigDecimal.ZERO) > 0;
        boolean accountExists = stockAccountRepository.findByUserKey(userKey).isPresent();
        if (!shouldCreateAccount && !accountExists) {
            return null;
        }

        marketLedgerFreezeGuard.acquireMutationPermit("auto-participant account funding");
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        StockAccount account = findOrCreateActiveAccount(userKey, now);
        account.assignParticipantCategory(StockAccountParticipantCategory.AUTO_PARTICIPANT, now);
        if (initialCashAmount.compareTo(BigDecimal.ZERO) > 0) {
            account.depositCash(initialCashAmount, now);
        }
        StockAccount savedAccount = stockAccountRepository.save(account);
        if (initialCashAmount.compareTo(BigDecimal.ZERO) > 0) {
            stockAccountCashFlowRepository.save(StockAccountCashFlow.adminDeposit(
                    savedAccount.getId(),
                    initialCashAmount,
                    createdBy(adminUserKey),
                    now
            ));
        }
        return savedAccount;
    }

    private StockAccount findOrCreateActiveAccount(String userKey, LocalDateTime now) {
        if (stockAccountRepository.findByUserKey(userKey).isEmpty()) {
            return StockAccount.open(userKey, null, null, null, now);
        }
        return stockAccountRepository.findByUserKeyAndStatusForUpdate(userKey, StockAccountStatus.ACTIVE)
                .orElseThrow(() -> StockException.conflict("Auto participant account is not active: " + userKey));
    }

    private BigDecimal normalizeInitialCashAmount(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw StockException.badRequest("Initial cash amount must be zero or greater");
        }
        return amount;
    }

    private String createdBy(String adminUserKey) {
        String normalized = MarketTextNormalizer.text(adminUserKey);
        return normalized.isBlank() ? AUTO_PARTICIPANT_GENERATE_CREATED_BY : normalized;
    }

    private AutoParticipantResponse toAutoParticipantResponse(StockAutoParticipant participant) {
        return toAutoParticipantResponse(
                participant,
                stockAccountRepository.findByUserKey(participant.getUserKey()).orElse(null)
        );
    }

    private AutoParticipantResponse toAutoParticipantResponse(StockAutoParticipant participant, StockAccount account) {
        return new AutoParticipantResponse(
                participant.getUserKey(),
                participant.getDisplayName(),
                Boolean.TRUE.equals(participant.getEnabled()),
                participant.getProfileType() == null
                        ? AutoParticipantProfileType.defaultType().name()
                        : participant.getProfileType().name(),
                stockAutoParticipantProfileConfigRepository.findById(participant.getProfileType())
                        .map(config -> config.getBehaviorModelVersion() == null
                                ? "V2"
                                : config.getBehaviorModelVersion().name())
                        .orElse("V2"),
                participant.getBehaviorSeed() == null ? null : participant.getBehaviorSeed().toString(),
                participant.getRecurringCashAmount(),
                participant.getRecurringCashIntervalValue(),
                participant.getRecurringCashIntervalUnit() == null ? null : participant.getRecurringCashIntervalUnit().name(),
                account == null ? null : account.getId(),
                account == null || account.getStatus() == null ? null : account.getStatus().name(),
                account == null ? null : account.getCashBalance(),
                participant.getCreatedAt(),
                participant.getUpdatedAt(),
                participant.getWithdrawnAt()
        );
    }

    private AutoParticipantProfileType parseAutoParticipantProfileType(String value) {
        String normalized = MarketTextNormalizer.text(value);
        if (normalized.isBlank()) {
            return AutoParticipantProfileType.defaultType();
        }
        try {
            return AutoParticipantProfileType.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw StockException.badRequest("Unknown auto participant profile type: " + value);
        }
    }

    private Long normalizeBehaviorSeed(String value) {
        String normalized = MarketTextNormalizer.text(value);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(normalized);
            if (parsed < 0) {
                throw StockException.badRequest("Behavior seed must be zero or greater");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw StockException.badRequest("Behavior seed must be a 64-bit integer");
        }
    }

}
