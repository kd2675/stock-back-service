package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.RecurringCashIntervalUnit;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AutoParticipantManagementService {

    private final StockAutoParticipantRepository stockAutoParticipantRepository;
    private final StockAccountRepository stockAccountRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public AutoParticipantResponse upsertAutoParticipant(String userKey, AutoParticipantRequest request) {
        String normalizedUserKey = normalizeText(userKey);
        if (normalizedUserKey.isBlank()) {
            throw StockException.badRequest("Auto participant user key is required");
        }
        if (normalizedUserKey.length() > 64) {
            throw StockException.badRequest("Auto participant user key must be 64 characters or less");
        }
        String displayName = normalizeText(request == null ? null : request.displayName());
        if (displayName.isBlank()) {
            throw StockException.badRequest("Auto participant display name is required");
        }
        if (displayName.length() > 80) {
            throw StockException.badRequest("Auto participant display name must be 80 characters or less");
        }
        AutoParticipantProfileType profileType = parseAutoParticipantProfileType(request == null ? null : request.profileType());
        BigDecimal recurringCashAmount = RecurringCashPolicy.normalizeAmount(request == null ? null : request.recurringCashAmount());
        BigDecimal recurringCashIntervalValue = RecurringCashPolicy.normalizeIntervalValue(
                request == null ? null : request.recurringCashIntervalValue(),
                recurringCashAmount
        );
        RecurringCashIntervalUnit recurringCashIntervalUnit = RecurringCashPolicy.normalizeIntervalUnit(
                request == null ? null : request.recurringCashIntervalUnit(),
                recurringCashAmount
        );
        StockAutoParticipant participant = stockAutoParticipantRepository.findById(normalizedUserKey)
                .map(existing -> {
                    existing.update(
                            displayName,
                            request == null ? null : request.enabled(),
                            profileType,
                            recurringCashAmount,
                            recurringCashIntervalValue,
                            recurringCashIntervalUnit
                    );
                    return existing;
                })
                .orElseGet(() -> StockAutoParticipant.create(
                        normalizedUserKey,
                        displayName,
                        request == null || request.enabled() == null || request.enabled(),
                        profileType,
                        recurringCashAmount,
                        recurringCashIntervalValue,
                        recurringCashIntervalUnit
                ));
        return toAutoParticipantResponse(stockAutoParticipantRepository.save(participant));
    }

    @Transactional
    public AutoParticipantResponse withdrawAutoParticipant(String userKey) {
        String normalizedUserKey = normalizeText(userKey);
        if (normalizedUserKey.isBlank()) {
            throw StockException.badRequest("Auto participant user key is required");
        }
        StockAutoParticipant participant = stockAutoParticipantRepository.findById(normalizedUserKey)
                .orElseThrow(() -> StockException.notFound("Unknown auto participant: " + normalizedUserKey));
        cancelOpenAutoParticipantOrders(normalizedUserKey);
        participant.withdraw();
        return toAutoParticipantResponse(stockAutoParticipantRepository.save(participant));
    }

    private void cancelOpenAutoParticipantOrders(String userKey) {
        Long accountId = stockAccountRepository.findByUserKeyAndStatus(userKey, StockAccountStatus.ACTIVE)
                .map(StockAccount::getId)
                .orElse(null);
        if (accountId == null) {
            return;
        }
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                """
                select id, symbol, side, quantity, filled_quantity, reserved_cash
                from stock_order
                where account_id = ?
                  and market_type = 'ORDER_BOOK'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                for update
                """,
                accountId
        );
        if (orders.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> order : orders) {
            String side = String.valueOf(order.get("side"));
            BigDecimal reservedCash = toBigDecimal(order.get("reserved_cash"));
            if ("BUY".equals(side) && reservedCash.compareTo(BigDecimal.ZERO) > 0) {
                jdbcTemplate.update(
                        "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where id = ?",
                        reservedCash,
                        now,
                        accountId
                );
            }
            if ("SELL".equals(side)) {
                long remainingQuantity = toLong(order.get("quantity")) - toLong(order.get("filled_quantity"));
                if (remainingQuantity > 0) {
                    jdbcTemplate.update(
                            """
                            update stock_holding
                            set reserved_quantity = case when reserved_quantity >= ? then reserved_quantity - ? else 0 end,
                                updated_at = ?
                            where account_id = ? and symbol = ?
                            """,
                            remainingQuantity,
                            remainingQuantity,
                            now,
                            accountId,
                            order.get("symbol")
                    );
                }
            }
            jdbcTemplate.update(
                    "update stock_order set status = 'CANCELLED', reserved_cash = 0, updated_at = ? where id = ?",
                    now,
                    order.get("id")
            );
        }
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
        String normalized = normalizeText(value);
        if (normalized.isBlank()) {
            return AutoParticipantProfileType.defaultType();
        }
        try {
            return AutoParticipantProfileType.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw StockException.badRequest("Unknown auto participant profile type: " + value);
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
