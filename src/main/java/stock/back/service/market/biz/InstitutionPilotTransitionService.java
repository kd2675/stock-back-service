package stock.back.service.market.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.InstitutionPilotActivationRequest;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

@Service
public class InstitutionPilotTransitionService {

    static final int MINIMUM_COMPLETED_SHADOW_TRADING_DAYS = 20;
    private static final int RECENT_FAILURE_LOOKBACK_DAYS = 20;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService marketSessionService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    public InstitutionPilotTransitionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SimulationClockService simulationClockService,
            SimulationMarketSessionService marketSessionService,
            MarketLedgerFreezeGuard marketLedgerFreezeGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.simulationClockService = simulationClockService;
        this.marketSessionService = marketSessionService;
        this.marketLedgerFreezeGuard = marketLedgerFreezeGuard;
    }

    @Transactional
    public void activatePilot(
            long portfolioId,
            InstitutionPilotActivationRequest request,
            String changedBy
    ) {
        if (portfolioId <= 0L) {
            throw StockException.badRequest("Institution portfolio id must be positive");
        }
        String symbol = MarketTextNormalizer.symbol(request == null ? null : request.symbol());
        if (symbol.isBlank()) {
            throw StockException.badRequest("PILOT symbol is required");
        }
        SimulationClockSnapshot clock = requirePausedPreOpen();
        LocalDate businessDate = marketLedgerFreezeGuard.acquireMutationPermit(
                "institution pilot activation"
        );
        if (!businessDate.equals(clock.simulationDate())) {
            throw StockException.conflict(
                    "Simulation date and active market business date must match"
            );
        }

        PortfolioTransitionRow portfolio = lockPortfolio(portfolioId);
        List<String> enabledSymbols = findEnabledSymbols(portfolioId);
        if ("PILOT".equals(portfolio.executionMode())) {
            if (enabledSymbols.equals(List.of(symbol))) {
                return;
            }
            throw StockException.conflict(
                    "Existing institution PILOT is not aligned to the requested symbol"
            );
        }
        if (!"SHADOW".equals(portfolio.executionMode())
                || !"ACTIVE".equals(portfolio.status())) {
            throw StockException.conflict(
                    "Only an active SHADOW institution portfolio can be promoted to PILOT"
            );
        }
        requireSelectedEnabledMandate(portfolioId, symbol);
        requireActiveMarket(symbol);
        requireEligibleDedicatedAccount(portfolio, businessDate);
        requireShadowEvidence(portfolioId, businessDate);
        requireCleanActivationDate(portfolio, businessDate);

        LocalDateTime now = clock.simulationDateTime();
        int mandateCount = jdbcTemplate.update(
                """
                update stock_institution_symbol_mandate
                   set enabled = case when symbol = ? then true else false end,
                       updated_at = ?
                 where portfolio_id = ?
                """,
                symbol,
                now,
                portfolioId
        );
        if (mandateCount <= 0) {
            throw new IllegalStateException(
                    "Institution PILOT mandate update changed no rows"
            );
        }
        long nextPolicyVersion = Math.addExact(portfolio.policyVersion(), 1L);
        int portfolioUpdated = jdbcTemplate.update(
                """
                update stock_institution_portfolio
                   set execution_mode = 'PILOT',
                       next_decision_at = ?,
                       policy_version = ?,
                       updated_at = ?
                 where id = ?
                   and status = 'ACTIVE'
                   and execution_mode = 'SHADOW'
                   and policy_version = ?
                """,
                businessDate.atTime(marketSessionService.openTime()),
                nextPolicyVersion,
                now,
                portfolioId,
                portfolio.policyVersion()
        );
        requireSingleUpdate(portfolioUpdated, "Institution PILOT portfolio transition");
        retireExistingPolicyVersions(portfolio.portfolioCode(), now);
        insertPilotPolicyVersion(
                portfolio,
                symbol,
                nextPolicyVersion,
                businessDate,
                normalizeReason(request),
                normalizeChangedBy(changedBy),
                now
        );
        if (!findEnabledSymbols(portfolioId).equals(List.of(symbol))) {
            throw new IllegalStateException(
                    "Institution PILOT must have exactly one enabled symbol mandate"
            );
        }
    }

    private SimulationClockSnapshot requirePausedPreOpen() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        if (clock.running()) {
            throw StockException.conflict(
                    "Pause the simulation clock before promoting an institution PILOT"
            );
        }
        if (marketSessionService.currentSession() != SimulationMarketSession.PRE_OPEN) {
            throw StockException.conflict(
                    "Institution PILOT promotion is only allowed during a paused pre-open"
            );
        }
        return clock;
    }

    private PortfolioTransitionRow lockPortfolio(long portfolioId) {
        return jdbcClient.sql(
                        """
                        select id, portfolio_code, participant_id, account_id,
                               execution_mode, status, policy_version
                          from stock_institution_portfolio
                         where id = ?
                         for update
                        """
                )
                .param(portfolioId)
                .query((rs, rowNum) -> new PortfolioTransitionRow(
                        rs.getLong("id"),
                        rs.getString("portfolio_code"),
                        rs.getLong("participant_id"),
                        rs.getLong("account_id"),
                        rs.getString("execution_mode"),
                        rs.getString("status"),
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElseThrow(() -> StockException.notFound(
                        "Institution portfolio not found: " + portfolioId
                ));
    }

    private List<String> findEnabledSymbols(long portfolioId) {
        return jdbcClient.sql(
                        """
                        select symbol
                          from stock_institution_symbol_mandate
                         where portfolio_id = ?
                           and enabled = true
                         order by symbol asc
                        """
                )
                .param(portfolioId)
                .query(String.class)
                .list();
    }

    private void requireSelectedEnabledMandate(long portfolioId, String symbol) {
        Integer count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_institution_symbol_mandate
                         where portfolio_id = ?
                           and symbol = ?
                           and enabled = true
                        """
                )
                .param(portfolioId)
                .param(symbol)
                .query(Integer.class)
                .single();
        if (count == null || count != 1) {
            throw StockException.conflict(
                    "PILOT symbol must be an enabled mandate of the SHADOW portfolio: " + symbol
            );
        }
    }

    private void requireActiveMarket(String symbol) {
        Boolean active = jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_order_book_instrument instrument
                              join stock_order_book_market_config market
                                on market.symbol = instrument.symbol
                               and market.enabled = true
                               and market.market_status in ('OPEN', 'CLOSED')
                              join stock_price price
                                on price.symbol = instrument.symbol
                               and price.current_price > 0
                             where instrument.symbol = ?
                               and instrument.enabled = true
                               and instrument.tradable_shares > 0
                        )
                        """
                )
                .param(symbol)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(active)) {
            throw StockException.conflict(
                    "Institution PILOT requires an enabled, non-halted order-book market: "
                            + symbol
            );
        }
    }

    private void requireEligibleDedicatedAccount(
            PortfolioTransitionRow portfolio,
            LocalDate businessDate
    ) {
        Integer eligibleMappingCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_account account
                          join stock_market_participant participant
                            on participant.id = ?
                          join stock_market_participant_account participant_account
                            on participant_account.participant_id = participant.id
                           and participant_account.account_id = account.id
                         where account.id = ?
                           and account.status = 'ACTIVE'
                           and account.participant_category = 'INSTITUTIONAL_INVESTOR'
                           and account.cash_balance >= 0
                           and account.self_trade_group_id is not null
                           and account.self_trade_group_id <> ''
                           and account.self_trade_group_id = participant.self_trade_group_id
                           and participant.status = 'ACTIVE'
                           and participant.participant_type = 'INSTITUTIONAL_INVESTOR'
                           and participant_account.account_role = 'INSTITUTIONAL_INVESTOR'
                           and participant_account.status = 'ACTIVE'
                           and participant_account.effective_from <= ?
                           and (
                               participant_account.effective_to is null
                               or participant_account.effective_to >= ?
                           )
                        """
                )
                .param(portfolio.participantId())
                .param(portfolio.accountId())
                .param(businessDate)
                .param(businessDate)
                .query(Integer.class)
                .single();
        if (eligibleMappingCount == null || eligibleMappingCount != 1) {
            throw StockException.conflict(
                    "Institution participant, dedicated account, role, or self-trade group is inconsistent"
            );
        }
        Integer invalidHoldingCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_holding
                         where account_id = ?
                           and (
                               quantity < 0
                               or reserved_quantity < 0
                               or reserved_quantity > quantity
                               or reserved_quantity > 0
                           )
                        """
                )
                .param(portfolio.accountId())
                .query(Integer.class)
                .single();
        if (invalidHoldingCount != null && invalidHoldingCount > 0) {
            throw StockException.conflict(
                    "Institution account has invalid or reserved holdings before PILOT activation"
            );
        }
    }

    private void requireShadowEvidence(long portfolioId, LocalDate businessDate) {
        Integer completedDays = jdbcClient.sql(
                        """
                        select count(distinct simulation_trade_date)
                          from stock_institution_decision_run
                         where portfolio_id = ?
                           and execution_mode = 'SHADOW'
                           and status = 'COMPLETED'
                           and simulation_trade_date < ?
                        """
                )
                .param(portfolioId)
                .param(businessDate)
                .query(Integer.class)
                .single();
        if (completedDays == null
                || completedDays < MINIMUM_COMPLETED_SHADOW_TRADING_DAYS) {
            throw StockException.conflict(
                    "Institution PILOT requires at least "
                            + MINIMUM_COMPLETED_SHADOW_TRADING_DAYS
                            + " completed SHADOW trading days; completed="
                            + (completedDays == null ? 0 : completedDays)
            );
        }
        Integer recentFailures = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_institution_decision_run
                         where portfolio_id = ?
                           and execution_mode = 'SHADOW'
                           and status = 'FAILED'
                           and simulation_trade_date >= ?
                           and simulation_trade_date < ?
                        """
                )
                .param(portfolioId)
                .param(businessDate.minusDays(RECENT_FAILURE_LOOKBACK_DAYS))
                .param(businessDate)
                .query(Integer.class)
                .single();
        if (recentFailures != null && recentFailures > 0) {
            throw StockException.conflict(
                    "Institution PILOT cannot start after recent SHADOW decision failures"
            );
        }
    }

    private void requireCleanActivationDate(
            PortfolioTransitionRow portfolio,
            LocalDate businessDate
    ) {
        Boolean dirty = jdbcClient.sql(
                        """
                        select (
                            exists(
                                select 1
                                  from stock_institution_decision_run
                                 where portfolio_id = ?
                                   and simulation_trade_date = ?
                            )
                            or exists(
                                select 1
                                  from stock_institution_daily_budget
                                 where portfolio_id = ?
                                   and simulation_trade_date = ?
                            )
                            or exists(
                                select 1
                                  from stock_institution_order_intent
                                 where portfolio_id = ?
                                   and status = 'PENDING'
                            )
                            or exists(
                                select 1
                                  from stock_order
                                 where account_id = ?
                                   and status in ('PENDING', 'PARTIALLY_FILLED')
                            )
                        )
                        """
                )
                .param(portfolio.portfolioId())
                .param(businessDate)
                .param(portfolio.portfolioId())
                .param(businessDate)
                .param(portfolio.portfolioId())
                .param(portfolio.accountId())
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(dirty)) {
            throw StockException.conflict(
                    "Institution PILOT requires a clean pre-open with no current-day decision, "
                            + "budget, pending intent, or open order"
            );
        }
    }

    private void retireExistingPolicyVersions(
            String portfolioCode,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set status = 'RETIRED',
                       updated_at = ?
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = ?
                   and status in ('SCHEDULED', 'ACTIVE')
                """,
                now,
                portfolioCode
        );
    }

    private void insertPilotPolicyVersion(
            PortfolioTransitionRow portfolio,
            String symbol,
            long policyVersion,
            LocalDate effectiveBusinessDate,
            String changeReason,
            String changedBy,
            LocalDateTime now
    ) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("transition", "SHADOW_TO_SINGLE_SYMBOL_PILOT");
        config.put("portfolioCode", portfolio.portfolioCode());
        config.put("executionMode", "PILOT");
        config.put("symbol", symbol);
        config.put(
                "minimumCompletedShadowTradingDays",
                MINIMUM_COMPLETED_SHADOW_TRADING_DAYS
        );
        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Institution PILOT policy JSON serialization failed",
                    ex
            );
        }
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_market_policy_version(
                            policy_scope, scope_key, version_no,
                            effective_business_date, status, config_json,
                            change_reason, changed_by, created_at, updated_at
                        ) values (
                            'INSTITUTIONAL_PORTFOLIO', ?, ?, ?,
                            'SCHEDULED', ?, ?, ?, ?, ?
                        )
                        """,
                        portfolio.portfolioCode(),
                        policyVersion,
                        effectiveBusinessDate,
                        configJson,
                        changeReason,
                        changedBy,
                        now,
                        now
                ),
                "Institution PILOT policy version"
        );
    }

    private String normalizeReason(InstitutionPilotActivationRequest request) {
        String reason = request == null ? null : request.changeReason();
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) {
            return "Promote reviewed institution shadow portfolio to one-symbol PILOT";
        }
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String normalizeChangedBy(String changedBy) {
        String normalized = changedBy == null ? "" : changedBy.trim();
        if (normalized.isBlank()) {
            return "SYSTEM";
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(
                    operation + " count mismatch: expected=1, actual=" + count
            );
        }
    }

    private record PortfolioTransitionRow(
            long portfolioId,
            String portfolioCode,
            long participantId,
            long accountId,
            String executionMode,
            String status,
            long policyVersion
    ) {
    }
}
