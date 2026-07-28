package stock.back.service.market.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.InstitutionPortfolioPolicyUpdateRequest;
import stock.back.service.market.vo.InstitutionSymbolPolicyUpdateRequest;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
public class InstitutionPortfolioPolicyControlService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6, RoundingMode.UNNECESSARY);
    private static final BigDecimal ONE = BigDecimal.ONE.setScale(6, RoundingMode.UNNECESSARY);
    private static final BigDecimal MAX_DAILY_PARTICIPATION_RATE =
            new BigDecimal("0.200000");
    private static final BigDecimal WEIGHT_SUM_TOLERANCE = new BigDecimal("0.000100");
    private static final int MAX_MANDATE_COUNT = 50;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService marketSessionService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    public InstitutionPortfolioPolicyControlService(
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

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public void schedulePolicy(
            long portfolioId,
            InstitutionPortfolioPolicyUpdateRequest request,
            String changedBy
    ) {
        if (portfolioId <= 0L) {
            throw StockException.badRequest("Institution portfolio id must be positive");
        }
        if (request == null) {
            throw StockException.badRequest("Institution portfolio policy request is required");
        }

        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDate permittedBusinessDate =
                marketLedgerFreezeGuard.acquireJdbcPreOpenMutationPermit(
                        "institution portfolio policy scheduling"
                );
        LocalDate effectiveBusinessDate = resolveNextMarketOpen(
                clock,
                permittedBusinessDate
        ).toLocalDate();
        PortfolioTarget target = lockPortfolio(portfolioId);
        if (!"LIVE".equals(target.executionMode())
                || (!"ACTIVE".equals(target.status()) && !"SUSPENDED".equals(target.status()))) {
            throw StockException.conflict(
                    "Only an active or suspended LIVE institution portfolio can schedule a policy"
            );
        }

        PolicyValues policy = validatePolicy(request);
        String reason = normalizeReason(request.changeReason());
        String actor = normalizeChangedBy(changedBy);
        LocalDateTime now = clock.simulationDateTime();
        ScheduledPolicy scheduled = lockScheduledPolicy(target.portfolioCode());
        long nextVersion = Math.addExact(target.policyVersion(), 1L);
        String configJson = policyConfigJson(target, policy);

        if (scheduled == null) {
            insertScheduledPolicy(
                    target.portfolioCode(),
                    nextVersion,
                    effectiveBusinessDate,
                    configJson,
                    reason,
                    actor,
                    now
            );
            return;
        }
        if (scheduled.version() == target.policyVersion()) {
            retireUnactivatedPolicy(scheduled.id(), now);
            insertScheduledPolicy(
                    target.portfolioCode(),
                    nextVersion,
                    effectiveBusinessDate,
                    configJson,
                    reason,
                    actor,
                    now
            );
            return;
        }
        if (scheduled.version() != nextVersion) {
            throw StockException.conflict(
                    "Scheduled institution policy version is not aligned with the active policy"
            );
        }
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_policy_version
                           set effective_business_date = ?,
                               config_json = ?,
                               change_reason = ?,
                               changed_by = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'SCHEDULED'
                        """,
                        effectiveBusinessDate,
                        configJson,
                        reason,
                        actor,
                        now,
                        scheduled.id()
                ),
                "Scheduled institution policy replacement"
        );
    }

    private PortfolioTarget lockPortfolio(long portfolioId) {
        return jdbcClient.sql(
                        """
                        select portfolio_code, execution_mode, status, policy_version
                          from stock_institution_portfolio
                         where id = :portfolioId
                         for update
                        """
                )
                .param("portfolioId", portfolioId)
                .query((rs, rowNum) -> new PortfolioTarget(
                        rs.getString("portfolio_code"),
                        rs.getString("execution_mode"),
                        rs.getString("status"),
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElseThrow(() -> StockException.notFound(
                        "Unknown institution portfolio: " + portfolioId
                ));
    }

    private ScheduledPolicy lockScheduledPolicy(String portfolioCode) {
        List<ScheduledPolicy> scheduled = jdbcClient.sql(
                        """
                        select id, version_no
                          from stock_market_policy_version
                         where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                           and scope_key = :portfolioCode
                           and status = 'SCHEDULED'
                         order by version_no asc
                         for update
                        """
                )
                .param("portfolioCode", portfolioCode)
                .query((rs, rowNum) -> new ScheduledPolicy(
                        rs.getLong("id"),
                        rs.getLong("version_no")
                ))
                .list();
        if (scheduled.size() > 1) {
            throw StockException.conflict(
                    "Institution portfolio has multiple scheduled policy versions: "
                            + portfolioCode
            );
        }
        return scheduled.isEmpty() ? null : scheduled.getFirst();
    }

    private PolicyValues validatePolicy(InstitutionPortfolioPolicyUpdateRequest request) {
        String displayName = normalizeDisplayName(request.displayName());
        String investmentStyle = InstitutionPortfolioPolicyCatalog
                .require(request.investmentStyle())
                .investmentStyle();
        BigDecimal baseStockAllocationRate = requiredRate(
                request.baseStockAllocationRate(),
                "Base stock allocation",
                true
        );
        BigDecimal minStockAllocationRate = requiredRate(
                request.minStockAllocationRate(),
                "Minimum stock allocation",
                false
        );
        BigDecimal maxStockAllocationRate = requiredRate(
                request.maxStockAllocationRate(),
                "Maximum stock allocation",
                true
        );
        if (minStockAllocationRate.compareTo(baseStockAllocationRate) > 0
                || baseStockAllocationRate.compareTo(maxStockAllocationRate) > 0) {
            throw StockException.badRequest(
                    "Institution stock allocation must satisfy minimum <= base <= maximum"
            );
        }
        BigDecimal primaryRegimeWeight = requiredRate(
                request.primaryRegimeWeight(),
                "Primary regime weight",
                false
        );
        BigDecimal assetPreferenceSensitivity = requiredRate(
                request.assetPreferenceSensitivity(),
                "Asset preference sensitivity",
                false
        );
        BigDecimal volatilitySensitivity = requiredRate(
                request.volatilitySensitivity(),
                "Volatility sensitivity",
                false
        );
        BigDecimal entryThresholdRate = requiredRate(
                request.entryThresholdRate(),
                "Entry threshold",
                false
        );
        BigDecimal exitThresholdRate = requiredRate(
                request.exitThresholdRate(),
                "Exit threshold",
                false
        );
        if (exitThresholdRate.compareTo(entryThresholdRate) > 0) {
            throw StockException.badRequest(
                    "Institution exit threshold cannot exceed the entry threshold"
            );
        }
        BigDecimal dailyTurnoverLimitRate = requiredRate(
                request.dailyTurnoverLimitRate(),
                "Daily turnover limit",
                true
        );
        BigDecimal maxDecisionTurnoverRate = requiredRate(
                request.maxDecisionTurnoverRate(),
                "Decision turnover limit",
                true
        );
        if (maxDecisionTurnoverRate.compareTo(dailyTurnoverLimitRate) > 0) {
            throw StockException.badRequest(
                    "Decision turnover limit cannot exceed the daily turnover limit"
            );
        }
        int decisionIntervalMinutes = request.decisionIntervalMinutes() == null
                ? 0
                : request.decisionIntervalMinutes();
        if (decisionIntervalMinutes < 5 || decisionIntervalMinutes > 1_440) {
            throw StockException.badRequest(
                    "Institution decision interval must be between 5 and 1440 minutes"
            );
        }

        List<InstitutionSymbolPolicyUpdateRequest> requestedMandates = request.mandates();
        if (requestedMandates.isEmpty() || requestedMandates.size() > MAX_MANDATE_COUNT) {
            throw StockException.badRequest(
                    "Institution policy must contain between 1 and "
                            + MAX_MANDATE_COUNT + " active symbol mandates"
            );
        }
        Set<String> marketSymbols = policyEligibleMarketSymbols();
        Set<String> seenSymbols = new HashSet<>();
        List<SymbolPolicyValues> mandates = new ArrayList<>(requestedMandates.size());
        BigDecimal baseWeightSum = ZERO;
        BigDecimal minimumAllocationSum = ZERO;
        BigDecimal maximumAllocationSum = ZERO;
        for (InstitutionSymbolPolicyUpdateRequest requested : requestedMandates) {
            SymbolPolicyValues mandate = validateMandate(requested, marketSymbols);
            if (!seenSymbols.add(mandate.symbol())) {
                throw StockException.badRequest(
                        "Institution policy contains a duplicate symbol: " + mandate.symbol()
                );
            }
            mandates.add(mandate);
            baseWeightSum = baseWeightSum.add(mandate.baseSymbolWeight());
            minimumAllocationSum =
                    minimumAllocationSum.add(mandate.minPortfolioAllocationRate());
            maximumAllocationSum =
                    maximumAllocationSum.add(mandate.maxPortfolioAllocationRate());
        }
        if (baseWeightSum.subtract(ONE).abs().compareTo(WEIGHT_SUM_TOLERANCE) > 0) {
            throw StockException.badRequest(
                    "Institution symbol base weights must sum to 1.0 within 0.01%"
            );
        }
        if (minimumAllocationSum.compareTo(minStockAllocationRate) > 0
                || maximumAllocationSum.compareTo(maxStockAllocationRate) < 0) {
            throw StockException.badRequest(
                    "Institution symbol allocation bounds cannot satisfy the portfolio stock band"
            );
        }
        return new PolicyValues(
                displayName,
                investmentStyle,
                baseStockAllocationRate,
                minStockAllocationRate,
                maxStockAllocationRate,
                primaryRegimeWeight,
                assetPreferenceSensitivity,
                volatilitySensitivity,
                entryThresholdRate,
                exitThresholdRate,
                dailyTurnoverLimitRate,
                maxDecisionTurnoverRate,
                decisionIntervalMinutes,
                List.copyOf(mandates)
        );
    }

    private SymbolPolicyValues validateMandate(
            InstitutionSymbolPolicyUpdateRequest request,
            Set<String> marketSymbols
    ) {
        if (request == null) {
            throw StockException.badRequest("Institution symbol mandate is required");
        }
        String symbol = normalizeSymbol(request.symbol());
        if (!marketSymbols.contains(symbol)) {
            throw StockException.badRequest(
                    "Institution mandate requires an active or activation-pending "
                            + "order-book symbol with a positive price: "
                            + symbol
            );
        }
        BigDecimal baseSymbolWeight = requiredRate(
                request.baseSymbolWeight(),
                "Symbol base weight",
                true
        );
        BigDecimal minPortfolioAllocationRate = requiredRate(
                request.minPortfolioAllocationRate(),
                "Symbol minimum allocation",
                false
        );
        BigDecimal maxPortfolioAllocationRate = requiredRate(
                request.maxPortfolioAllocationRate(),
                "Symbol maximum allocation",
                true
        );
        if (minPortfolioAllocationRate.compareTo(maxPortfolioAllocationRate) > 0) {
            throw StockException.badRequest(
                    "Symbol minimum allocation cannot exceed its maximum: " + symbol
            );
        }
        long referenceDailyVolume = request.referenceDailyVolume() == null
                ? 0L
                : request.referenceDailyVolume();
        if (referenceDailyVolume <= 0L) {
            throw StockException.badRequest(
                    "Institution reference daily volume must be positive: " + symbol
            );
        }
        BigDecimal dailyParticipationRate = requiredDecimal(
                request.dailyParticipationRate(),
                "Symbol daily participation",
                new BigDecimal("0.000001"),
                MAX_DAILY_PARTICIPATION_RATE
        );
        return new SymbolPolicyValues(
                symbol,
                baseSymbolWeight,
                minPortfolioAllocationRate,
                maxPortfolioAllocationRate,
                signedSensitivity(request.pricePressureSensitivity(), "Price pressure", symbol),
                signedSensitivity(request.momentumSensitivity(), "Momentum", symbol),
                signedSensitivity(request.valueSensitivity(), "Value", symbol),
                signedSensitivity(request.reportSensitivity(), "Report", symbol),
                referenceDailyVolume,
                dailyParticipationRate
        );
    }

    private Set<String> policyEligibleMarketSymbols() {
        return Set.copyOf(jdbcClient.sql(
                        """
                        select instrument.symbol
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                           and (
                               market.market_status = 'CLOSED'
                               or (
                                   market.enabled = true
                                   and market.market_status = 'OPEN'
                               )
                           )
                          join stock_price price
                            on price.symbol = instrument.symbol
                           and price.current_price > 0
                         where instrument.enabled = true
                           and instrument.tradable_shares > 0
                         order by instrument.symbol asc
                        """
                )
                .query(String.class)
                .list());
    }

    private String policyConfigJson(PortfolioTarget target, PolicyValues policy) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("preset", "INDEPENDENT_INSTITUTION_PORTFOLIO_V2");
        config.put("portfolioCode", target.portfolioCode());
        config.put("executionMode", target.executionMode());
        config.put("displayName", policy.displayName());
        config.put("investmentStyle", policy.investmentStyle());
        config.put("baseStockAllocationRate", policy.baseStockAllocationRate());
        config.put("minStockAllocationRate", policy.minStockAllocationRate());
        config.put("maxStockAllocationRate", policy.maxStockAllocationRate());
        config.put("primaryRegimeWeight", policy.primaryRegimeWeight());
        config.put("assetPreferenceSensitivity", policy.assetPreferenceSensitivity());
        config.put("volatilitySensitivity", policy.volatilitySensitivity());
        config.put("entryThresholdRate", policy.entryThresholdRate());
        config.put("exitThresholdRate", policy.exitThresholdRate());
        config.put("dailyTurnoverLimitRate", policy.dailyTurnoverLimitRate());
        config.put("maxDecisionTurnoverRate", policy.maxDecisionTurnoverRate());
        config.put("decisionIntervalMinutes", policy.decisionIntervalMinutes());
        config.put("mandates", policy.mandates().stream().map(mandate -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("symbol", mandate.symbol());
            value.put("baseSymbolWeight", mandate.baseSymbolWeight());
            value.put("minPortfolioAllocationRate", mandate.minPortfolioAllocationRate());
            value.put("maxPortfolioAllocationRate", mandate.maxPortfolioAllocationRate());
            value.put("pricePressureSensitivity", mandate.pricePressureSensitivity());
            value.put("momentumSensitivity", mandate.momentumSensitivity());
            value.put("valueSensitivity", mandate.valueSensitivity());
            value.put("reportSensitivity", mandate.reportSensitivity());
            value.put("referenceDailyVolume", mandate.referenceDailyVolume());
            value.put("dailyParticipationRate", mandate.dailyParticipationRate());
            return value;
        }).toList());
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Institution scheduled policy JSON serialization failed",
                    ex
            );
        }
    }

    private void insertScheduledPolicy(
            String portfolioCode,
            long version,
            LocalDate effectiveBusinessDate,
            String configJson,
            String reason,
            String changedBy,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_market_policy_version(
                            policy_scope, scope_key, version_no,
                            effective_business_date, status, config_json,
                            change_reason, changed_by, created_at, updated_at
                        ) values (
                            'INSTITUTIONAL_PORTFOLIO', ?, ?,
                            ?, 'SCHEDULED', ?, ?, ?, ?, ?
                        )
                        """,
                        portfolioCode,
                        version,
                        effectiveBusinessDate,
                        configJson,
                        reason,
                        changedBy,
                        now,
                        now
                ),
                "Institution scheduled policy insert"
        );
    }

    private void retireUnactivatedPolicy(long policyId, LocalDateTime now) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_policy_version
                           set status = 'RETIRED',
                               updated_at = ?
                         where id = ?
                           and status = 'SCHEDULED'
                        """,
                        now,
                        policyId
                ),
                "Unactivated institution policy retirement"
        );
    }

    private LocalDateTime resolveNextMarketOpen(
            SimulationClockSnapshot clock,
            LocalDate permittedBusinessDate
    ) {
        LocalDateTime currentDateOpen =
                clock.simulationDate().atTime(marketSessionService.openTime());
        LocalDateTime nextMarketOpen = clock.simulationDateTime().isBefore(currentDateOpen)
                ? currentDateOpen
                : currentDateOpen.plusDays(1);
        LocalDate activationBusinessDate = nextMarketOpen.toLocalDate();
        if (activationBusinessDate.isBefore(permittedBusinessDate)
                || activationBusinessDate.isAfter(permittedBusinessDate.plusDays(1))) {
            throw StockException.conflict(
                    "Institution policy activation date is inconsistent with the market business state: "
                            + "permitted=" + permittedBusinessDate
                            + ", activation=" + activationBusinessDate
            );
        }
        return nextMarketOpen;
    }

    private String normalizeDisplayName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 120) {
            throw StockException.badRequest(
                    "Institution display name must be between 1 and 120 characters"
            );
        }
        return normalized;
    }

    private String normalizeSymbol(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw StockException.badRequest(
                    "Institution symbol must be 2-20 uppercase letters or digits"
            );
        }
        return normalized;
    }

    private BigDecimal requiredRate(
            BigDecimal value,
            String label,
            boolean positive
    ) {
        return requiredDecimal(
                value,
                label,
                positive ? new BigDecimal("0.000001") : ZERO,
                ONE
        );
    }

    private BigDecimal signedSensitivity(BigDecimal value, String label, String symbol) {
        try {
            return requiredDecimal(value, label, ONE.negate(), ONE);
        } catch (StockException ex) {
            throw StockException.badRequest(ex.getMessage() + ": " + symbol);
        }
    }

    private BigDecimal requiredDecimal(
            BigDecimal value,
            String label,
            BigDecimal minimum,
            BigDecimal maximum
    ) {
        if (value == null
                || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw StockException.badRequest(
                    label + " must be between " + minimum + " and " + maximum
            );
        }
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private String normalizeReason(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "Schedule institution portfolio policy for the next market open";
        }
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String normalizeChangedBy(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "SYSTEM";
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private void requireSingleUpdate(int updated, String operation) {
        if (updated != 1) {
            throw new IllegalStateException(operation + " must update exactly one row");
        }
    }

    private record PortfolioTarget(
            String portfolioCode,
            String executionMode,
            String status,
            long policyVersion
    ) {
    }

    private record ScheduledPolicy(long id, long version) {
    }

    private record PolicyValues(
            String displayName,
            String investmentStyle,
            BigDecimal baseStockAllocationRate,
            BigDecimal minStockAllocationRate,
            BigDecimal maxStockAllocationRate,
            BigDecimal primaryRegimeWeight,
            BigDecimal assetPreferenceSensitivity,
            BigDecimal volatilitySensitivity,
            BigDecimal entryThresholdRate,
            BigDecimal exitThresholdRate,
            BigDecimal dailyTurnoverLimitRate,
            BigDecimal maxDecisionTurnoverRate,
            int decisionIntervalMinutes,
            List<SymbolPolicyValues> mandates
    ) {
    }

    private record SymbolPolicyValues(
            String symbol,
            BigDecimal baseSymbolWeight,
            BigDecimal minPortfolioAllocationRate,
            BigDecimal maxPortfolioAllocationRate,
            BigDecimal pricePressureSensitivity,
            BigDecimal momentumSensitivity,
            BigDecimal valueSensitivity,
            BigDecimal reportSensitivity,
            long referenceDailyVolume,
            BigDecimal dailyParticipationRate
    ) {
    }

}
