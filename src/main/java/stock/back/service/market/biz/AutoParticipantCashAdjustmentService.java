package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentResponse;

import java.math.BigDecimal;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AutoParticipantCashAdjustmentService {

    private final StockAutoParticipantRepository stockAutoParticipantRepository;
    private final StockAccountRepository stockAccountRepository;
    private final StockAccountCashFlowRepository stockAccountCashFlowRepository;

    @Transactional
    public AutoParticipantCashAdjustmentResponse adjustAutoParticipantCash(
            String userKey,
            AutoParticipantCashAdjustmentRequest request,
            String adminUserKey
    ) {
        String normalizedUserKey = normalizeText(userKey);
        if (normalizedUserKey.isBlank()) {
            throw StockException.badRequest("Auto participant user key is required");
        }
        StockAutoParticipant participant = stockAutoParticipantRepository.findById(normalizedUserKey)
                .orElseThrow(() -> StockException.notFound("Unknown auto participant: " + normalizedUserKey));
        if (participant.getWithdrawnAt() != null) {
            throw StockException.notFound("Unknown auto participant: " + normalizedUserKey);
        }

        BigDecimal amount = request == null ? null : request.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Adjustment amount must be positive");
        }
        String adjustmentType = normalizeText(request.adjustmentType()).toUpperCase(Locale.ROOT);
        if (!"DEPOSIT".equals(adjustmentType) && !"WITHDRAW".equals(adjustmentType)) {
            throw StockException.badRequest("Adjustment type must be DEPOSIT or WITHDRAW");
        }

        StockAccount account = stockAccountRepository.findByUserKeyAndStatusForUpdate(normalizedUserKey, StockAccountStatus.ACTIVE)
                .orElseThrow(() -> StockException.notFound("Auto participant account is not opened yet: " + normalizedUserKey));
        if ("DEPOSIT".equals(adjustmentType)) {
            account.depositCash(amount);
            stockAccountCashFlowRepository.save(StockAccountCashFlow.adminDeposit(account.getId(), amount, normalizeText(adminUserKey)));
        } else if (!account.withdrawCash(amount)) {
            throw StockException.badRequest("Insufficient auto participant cash balance");
        } else {
            stockAccountCashFlowRepository.save(StockAccountCashFlow.adminWithdraw(account.getId(), amount, normalizeText(adminUserKey)));
        }
        return new AutoParticipantCashAdjustmentResponse(
                normalizedUserKey,
                adjustmentType,
                amount,
                account.getCashBalance(),
                account.getUpdatedAt()
        );
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
