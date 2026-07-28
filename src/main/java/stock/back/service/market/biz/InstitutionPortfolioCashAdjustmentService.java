package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.InstitutionPortfolioCashAdjustmentRequest;

@Service
public class InstitutionPortfolioCashAdjustmentService {

    private static final BigDecimal MAX_ADJUSTMENT_AMOUNT =
            new BigDecimal("99999999999999999.99");

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    public InstitutionPortfolioCashAdjustmentService(
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService,
            MarketLedgerFreezeGuard marketLedgerFreezeGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationClockService = simulationClockService;
        this.marketLedgerFreezeGuard = marketLedgerFreezeGuard;
    }

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public void adjustCash(
            long portfolioId,
            InstitutionPortfolioCashAdjustmentRequest request,
            String changedBy
    ) {
        if (portfolioId <= 0L) {
            throw StockException.badRequest("Institution portfolio id must be positive");
        }
        String adjustmentType = normalizeAdjustmentType(
                request == null ? null : request.adjustmentType()
        );
        BigDecimal amount = normalizeAmount(request == null ? null : request.amount());

        marketLedgerFreezeGuard.acquireJdbcMutationPermit(
                "institution portfolio cash adjustment"
        );
        InstitutionCashTarget target = lockTarget(portfolioId);
        validateTarget(target);
        validateBalanceChange(target, adjustmentType, amount);

        LocalDateTime adjustedAt = simulationClockService.currentMarketDateTime();
        int updated = "DEPOSIT".equals(adjustmentType)
                ? deposit(target, amount, adjustedAt)
                : withdraw(target, amount, adjustedAt);
        if (updated != 1) {
            throw StockException.conflict(
                    "Institution cash balance changed while the adjustment was being applied"
            );
        }
        insertCashFlow(
                target.accountId(),
                adjustmentType,
                amount,
                normalizeActor(changedBy),
                adjustedAt
        );
    }

    private InstitutionCashTarget lockTarget(long portfolioId) {
        return jdbcClient.sql(
                        """
                        select portfolio.portfolio_code,
                               portfolio.execution_mode,
                               portfolio.status as portfolio_status,
                               participant.participant_type,
                               account.id as account_id,
                               account.user_key,
                               account.participant_category,
                               account.status as account_status,
                               account.cash_balance
                          from stock_institution_portfolio portfolio
                          join stock_market_participant participant
                            on participant.id = portfolio.participant_id
                          join stock_account account
                            on account.id = portfolio.account_id
                         where portfolio.id = :portfolioId
                         for update
                        """
                )
                .param("portfolioId", portfolioId)
                .query((rs, rowNum) -> new InstitutionCashTarget(
                        rs.getString("portfolio_code"),
                        rs.getString("execution_mode"),
                        rs.getString("portfolio_status"),
                        rs.getString("participant_type"),
                        rs.getString("participant_category"),
                        rs.getLong("account_id"),
                        rs.getString("user_key"),
                        rs.getString("account_status"),
                        rs.getBigDecimal("cash_balance")
                ))
                .optional()
                .orElseThrow(() -> StockException.notFound(
                        "Unknown institution portfolio: " + portfolioId
                ));
    }

    private void validateTarget(InstitutionCashTarget target) {
        String expectedUserKey = "stock-institution-"
                + target.portfolioCode().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!"LIVE".equals(target.executionMode())
                || (!"ACTIVE".equals(target.portfolioStatus())
                && !"SUSPENDED".equals(target.portfolioStatus()))
                || !"INSTITUTIONAL_INVESTOR".equals(target.participantType())
                || !"INSTITUTIONAL_INVESTOR".equals(target.participantCategory())
                || !"ACTIVE".equals(target.accountStatus())
                || !expectedUserKey.equals(target.accountUserKey())) {
            throw StockException.conflict(
                    "Institution portfolio cash target identity is not valid: "
                            + target.portfolioCode()
            );
        }
    }

    private void validateBalanceChange(
            InstitutionCashTarget target,
            String adjustmentType,
            BigDecimal amount
    ) {
        BigDecimal availableCash = target.availableCash();
        if (availableCash == null || availableCash.signum() < 0) {
            throw StockException.conflict(
                    "Institution account has an invalid available cash balance: "
                            + target.portfolioCode()
            );
        }
        if ("DEPOSIT".equals(adjustmentType)
                && availableCash.add(amount).compareTo(MAX_ADJUSTMENT_AMOUNT) > 0) {
            throw StockException.badRequest(
                    "Institution cash deposit would exceed the account balance limit"
            );
        }
        if ("WITHDRAW".equals(adjustmentType)
                && availableCash.compareTo(amount) < 0) {
            throw StockException.badRequest(
                    "Institution available cash is insufficient for this withdrawal"
            );
        }
    }

    private int deposit(
            InstitutionCashTarget target,
            BigDecimal amount,
            LocalDateTime adjustedAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance + ?,
                       updated_at = ?
                 where id = ?
                   and status = 'ACTIVE'
                """,
                amount,
                adjustedAt,
                target.accountId()
        );
    }

    private int withdraw(
            InstitutionCashTarget target,
            BigDecimal amount,
            LocalDateTime adjustedAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance - ?,
                       updated_at = ?
                 where id = ?
                   and status = 'ACTIVE'
                   and cash_balance >= ?
                """,
                amount,
                adjustedAt,
                target.accountId(),
                amount
        );
    }

    private void insertCashFlow(
            long accountId,
            String adjustmentType,
            BigDecimal amount,
            String actor,
            LocalDateTime adjustedAt
    ) {
        int inserted = jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(
                    account_id, flow_type, amount, reason, created_by,
                    corporate_action_id, corporate_action_entitlement_id,
                    effective_business_date, created_at
                ) values (?, ?, ?, ?, ?, null, null, null, ?)
                """,
                accountId,
                adjustmentType,
                amount,
                "DEPOSIT".equals(adjustmentType) ? "ADMIN_DEPOSIT" : "ADMIN_WITHDRAW",
                actor,
                adjustedAt
        );
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Institution cash-flow adjustment must write exactly one ledger row"
            );
        }
    }

    private String normalizeAdjustmentType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"DEPOSIT".equals(normalized) && !"WITHDRAW".equals(normalized)) {
            throw StockException.badRequest(
                    "Institution cash adjustment type must be DEPOSIT or WITHDRAW"
            );
        }
        return normalized;
    }

    private BigDecimal normalizeAmount(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.compareTo(MAX_ADJUSTMENT_AMOUNT) > 0) {
            throw StockException.badRequest(
                    "Institution cash adjustment amount must be positive and fit the account"
            );
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw StockException.badRequest(
                    "Institution cash adjustment amount supports at most two decimal places"
            );
        }
    }

    private String normalizeActor(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "ADMIN";
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private record InstitutionCashTarget(
            String portfolioCode,
            String executionMode,
            String portfolioStatus,
            String participantType,
            String participantCategory,
            long accountId,
            String accountUserKey,
            String accountStatus,
            BigDecimal availableCash
    ) {
    }
}
