package stock.back.service.market.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.InstitutionPortfolioCreateRequest;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

@Service
public class InstitutionPortfolioProvisionService {

    private static final BigDecimal DEFAULT_AUM_RATE = new BigDecimal("0.010000");
    private static final BigDecimal MIN_AUM_RATE = new BigDecimal("0.001000");
    private static final BigDecimal MAX_AUM_RATE = new BigDecimal("0.020000");
    private static final BigDecimal REFERENCE_VOLUME_RATE = new BigDecimal("0.030000");
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService marketSessionService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    public InstitutionPortfolioProvisionService(
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
    public long createPortfolio(
            InstitutionPortfolioCreateRequest request,
            String changedBy
    ) {
        if (request == null) {
            throw StockException.badRequest("Institution portfolio request is required");
        }
        String portfolioCode = normalizePortfolioCode(request.portfolioCode());
        String displayName = normalizeDisplayName(request.displayName());
        InstitutionPortfolioPolicyCatalog.Policy style =
                InstitutionPortfolioPolicyCatalog.require(request.investmentStyle());
        PresetPolicy policy = provisioningPolicy(portfolioCode, displayName, style);
        BigDecimal aumRate = normalizeAumRate(request);
        String changeReason = normalizeChangeReason(request);
        String normalizedChangedBy = normalizeChangedBy(changedBy);
        requirePortfolioCodeAvailable(portfolioCode);

        SimulationClockSnapshot clock = requirePausedPreOpen();
        LocalDate activeBusinessDate = marketLedgerFreezeGuard.acquireJdbcPreOpenMutationPermit(
                "institution portfolio creation"
        );
        if (!activeBusinessDate.equals(clock.simulationDate())) {
            throw StockException.conflict(
                    "Simulation date and active market business date must match"
            );
        }
        List<MarketSymbol> activeMarketSymbols = findActiveMarketSymbols();
        List<MarketSymbol> symbols = selectMarketSymbols(
                activeMarketSymbols,
                request.symbols()
        );
        if (symbols.isEmpty()) {
            throw StockException.conflict(
                    "At least one active order-book symbol with a positive price is required"
            );
        }
        BigDecimal totalMarketCapitalization = activeMarketSymbols.stream()
                .map(MarketSymbol::marketCapitalization)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal selectedMarketCapitalization = symbols.stream()
                .map(MarketSymbol::marketCapitalization)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal initialCash = totalMarketCapitalization.multiply(aumRate)
                .setScale(2, RoundingMode.DOWN);
        if (initialCash.signum() <= 0) {
            throw StockException.badRequest("Institution initial AUM must be positive");
        }
        LocalDateTime now = clock.simulationDateTime();
        LocalDate firstDecisionDate = activeBusinessDate;
        LocalDateTime firstDecisionAt = firstDecisionDate.atTime(marketSessionService.openTime());
        Map<String, BigDecimal> marketWeights = marketWeights(
                symbols,
                selectedMarketCapitalization
        );

        long participantId = insertParticipant(policy, now);
        long accountId = insertAccount(policy, initialCash, now);
        insertParticipantAccount(
                participantId,
                accountId,
                policy,
                firstDecisionDate,
                now
        );
        long portfolioId = insertPortfolio(
                participantId,
                accountId,
                policy,
                firstDecisionAt,
                now
        );
        insertMandates(
                portfolioId,
                policy,
                symbols,
                marketWeights,
                now
        );
        insertOpeningGrant(
                accountId,
                initialCash,
                activeBusinessDate,
                normalizedChangedBy,
                now
        );
        insertPolicyVersion(
                policy,
                aumRate,
                initialCash,
                firstDecisionDate,
                changeReason,
                normalizedChangedBy,
                now
        );
        return portfolioId;
    }

    private SimulationClockSnapshot requirePausedPreOpen() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        if (clock.running()) {
            throw StockException.conflict(
                    "Pause the simulation clock before creating institution opening capital"
            );
        }
        if (marketSessionService.currentSession() != SimulationMarketSession.PRE_OPEN) {
            throw StockException.conflict(
                    "Institution portfolios can only be created during a paused pre-open"
            );
        }
        return clock;
    }

    private BigDecimal normalizeAumRate(InstitutionPortfolioCreateRequest request) {
        BigDecimal rate = request.institutionAumRateOfMarketCap() == null
                ? DEFAULT_AUM_RATE
                : request.institutionAumRateOfMarketCap();
        if (rate.compareTo(MIN_AUM_RATE) < 0 || rate.compareTo(MAX_AUM_RATE) > 0) {
            throw StockException.badRequest(
                    "Institution AUM rate must be between 0.001 and 0.020 per portfolio"
            );
        }
        return rate.setScale(6, RoundingMode.HALF_UP);
    }

    private String normalizeChangeReason(InstitutionPortfolioCreateRequest request) {
        String reason = request.changeReason();
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) {
            return "Create one institution portfolio in live mode";
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

    private String normalizePortfolioCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{3,24}")) {
            throw StockException.badRequest(
                    "Portfolio code must be 3-24 uppercase letters, digits, or underscores"
            );
        }
        return normalized;
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

    private Set<String> normalizeSymbols(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String symbol = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!symbol.matches("[A-Z0-9]{2,20}")) {
                throw StockException.badRequest(
                        "Institution symbol must be 2-20 uppercase letters or digits"
                );
            }
            normalized.add(symbol);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private void requirePortfolioCodeAvailable(String portfolioCode) {
        Boolean exists = jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_institution_portfolio
                             where portfolio_code = ?
                        )
                        """
                )
                .param(portfolioCode)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(exists)) {
            throw StockException.conflict(
                    "Institution portfolio code already exists: " + portfolioCode
            );
        }
    }

    private List<MarketSymbol> findActiveMarketSymbols() {
        return jdbcClient.sql(
                        """
                        select instrument.symbol,
                               instrument.tradable_shares,
                               price.current_price
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                           and market.enabled = true
                           and market.market_status in ('OPEN', 'CLOSED')
                          join stock_price price
                            on price.symbol = instrument.symbol
                           and price.current_price > 0
                         where instrument.enabled = true
                           and instrument.tradable_shares > 0
                         order by instrument.symbol asc
                        """
                )
                .query((rs, rowNum) -> new MarketSymbol(
                        rs.getString("symbol"),
                        rs.getLong("tradable_shares"),
                        rs.getBigDecimal("current_price")
                ))
                .list();
    }

    private List<MarketSymbol> selectMarketSymbols(
            List<MarketSymbol> available,
            List<String> requestedSymbols
    ) {
        Set<String> normalizedSymbols = normalizeSymbols(requestedSymbols);
        if (normalizedSymbols.isEmpty()) {
            return available;
        }
        Map<String, MarketSymbol> availableBySymbol = available.stream()
                .collect(java.util.stream.Collectors.toMap(
                        MarketSymbol::symbol,
                        symbol -> symbol,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<String> missing = normalizedSymbols.stream()
                .filter(symbol -> !availableBySymbol.containsKey(symbol))
                .toList();
        if (!missing.isEmpty()) {
            throw StockException.badRequest(
                    "Institution symbols must be active order-book symbols with positive prices: "
                            + String.join(",", missing)
            );
        }
        return normalizedSymbols.stream()
                .map(availableBySymbol::get)
                .toList();
    }

    private Map<String, BigDecimal> marketWeights(
            List<MarketSymbol> symbols,
            BigDecimal totalMarketCapitalization
    ) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal assigned = BigDecimal.ZERO;
        for (int index = 0; index < symbols.size(); index++) {
            MarketSymbol symbol = symbols.get(index);
            BigDecimal weight = index == symbols.size() - 1
                    ? BigDecimal.ONE.subtract(assigned)
                    : symbol.marketCapitalization().divide(
                            totalMarketCapitalization,
                            8,
                            RoundingMode.HALF_UP
                    );
            weight = weight.max(new BigDecimal("0.000001")).min(BigDecimal.ONE);
            result.put(symbol.symbol(), weight);
            assigned = assigned.add(weight);
        }
        return Map.copyOf(result);
    }

    private long insertParticipant(PresetPolicy preset, LocalDateTime now) {
        return insertWithGeneratedKey(
                """
                insert into stock_market_participant(
                    participant_code, display_name, participant_type,
                    status, self_trade_group_id, created_at, updated_at
                ) values (?, ?, 'INSTITUTIONAL_INVESTOR', 'ACTIVE', ?, ?, ?)
                """,
                statement -> {
                    statement.setString(1, preset.participantCode());
                    statement.setString(2, preset.displayName());
                    statement.setString(3, preset.selfTradeGroupId());
                    statement.setObject(4, now);
                    statement.setObject(5, now);
                }
        );
    }

    private long insertAccount(
            PresetPolicy preset,
            BigDecimal initialCash,
            LocalDateTime now
    ) {
        return insertWithGeneratedKey(
                """
                insert into stock_account(
                    user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (?, ?, 'ACTIVE', 'INSTITUTIONAL_INVESTOR', ?, ?, ?, ?)
                """,
                statement -> {
                    statement.setString(1, preset.userKey());
                    statement.setString(2, preset.accountCode());
                    statement.setString(3, preset.selfTradeGroupId());
                    statement.setBigDecimal(4, initialCash);
                    statement.setObject(5, now);
                    statement.setObject(6, now);
                }
        );
    }

    private void insertParticipantAccount(
            long participantId,
            long accountId,
            PresetPolicy preset,
            LocalDate effectiveFrom,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                insert into stock_market_participant_account(
                    participant_id, account_id, account_role, desk_code,
                    effective_from, effective_to, status, created_at, updated_at
                ) values (?, ?, 'INSTITUTIONAL_INVESTOR', ?, ?, null, 'ACTIVE', ?, ?)
                """,
                participantId,
                accountId,
                preset.deskCode(),
                effectiveFrom,
                now,
                now
        );
    }

    private long insertPortfolio(
            long participantId,
            long accountId,
            PresetPolicy preset,
            LocalDateTime firstDecisionAt,
            LocalDateTime now
    ) {
        return insertWithGeneratedKey(
                """
                insert into stock_institution_portfolio(
                    participant_id, account_id, portfolio_code, display_name,
                    investment_style, execution_mode, status,
                    base_stock_allocation_rate, min_stock_allocation_rate,
                    max_stock_allocation_rate, primary_regime_weight,
                    asset_preference_sensitivity, volatility_sensitivity,
                    entry_threshold_rate, exit_threshold_rate,
                    daily_turnover_limit_rate, max_decision_turnover_rate,
                    decision_interval_minutes, next_decision_at, policy_version,
                    created_at, updated_at
                ) values (
                    ?, ?, ?, ?, ?, 'LIVE', 'ACTIVE',
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?
                )
                """,
                statement -> {
                    int index = 1;
                    statement.setLong(index++, participantId);
                    statement.setLong(index++, accountId);
                    statement.setString(index++, preset.portfolioCode());
                    statement.setString(index++, preset.displayName());
                    statement.setString(index++, preset.investmentStyle());
                    statement.setBigDecimal(index++, preset.baseStockAllocationRate());
                    statement.setBigDecimal(index++, preset.minStockAllocationRate());
                    statement.setBigDecimal(index++, preset.maxStockAllocationRate());
                    statement.setBigDecimal(index++, preset.primaryRegimeWeight());
                    statement.setBigDecimal(index++, preset.assetPreferenceSensitivity());
                    statement.setBigDecimal(index++, preset.volatilitySensitivity());
                    statement.setBigDecimal(index++, preset.entryThresholdRate());
                    statement.setBigDecimal(index++, preset.exitThresholdRate());
                    statement.setBigDecimal(index++, preset.dailyTurnoverLimitRate());
                    statement.setBigDecimal(index++, preset.maxDecisionTurnoverRate());
                    statement.setInt(index++, preset.decisionIntervalMinutes());
                    statement.setObject(index++, firstDecisionAt);
                    statement.setObject(index++, now);
                    statement.setObject(index, now);
                }
        );
    }

    private void insertMandates(
            long portfolioId,
            PresetPolicy preset,
            List<MarketSymbol> symbols,
            Map<String, BigDecimal> marketWeights,
            LocalDateTime now
    ) {
        BigDecimal maximumSymbolAllocation = symbols.size() == 1
                ? preset.maxStockAllocationRate()
                : symbols.size() <= 3
                        ? new BigDecimal("0.500000")
                        : new BigDecimal("0.300000");
        jdbcTemplate.batchUpdate(
                """
                insert into stock_institution_symbol_mandate(
                    portfolio_id, symbol, base_symbol_weight,
                    min_portfolio_allocation_rate, max_portfolio_allocation_rate,
                    price_pressure_sensitivity, momentum_sensitivity,
                    value_sensitivity, report_sensitivity,
                    reference_daily_volume, daily_participation_rate,
                    enabled, created_at, updated_at
                ) values (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, true, ?, ?)
                """,
                symbols,
                symbols.size(),
                (statement, symbol) -> {
                    statement.setLong(1, portfolioId);
                    statement.setString(2, symbol.symbol());
                    statement.setBigDecimal(3, marketWeights.get(symbol.symbol()));
                    statement.setBigDecimal(4, maximumSymbolAllocation);
                    statement.setBigDecimal(5, preset.pricePressureSensitivity());
                    statement.setBigDecimal(6, preset.momentumSensitivity());
                    statement.setBigDecimal(7, preset.valueSensitivity());
                    statement.setBigDecimal(8, preset.reportSensitivity());
                    statement.setLong(9, referenceDailyVolume(symbol.tradableShares()));
                    statement.setBigDecimal(10, preset.dailyParticipationRate());
                    statement.setObject(11, now);
                    statement.setObject(12, now);
                }
        );
    }

    private long referenceDailyVolume(long tradableShares) {
        BigDecimal reference = BigDecimal.valueOf(tradableShares)
                .multiply(REFERENCE_VOLUME_RATE);
        return Math.max(1L, reference.setScale(0, RoundingMode.DOWN).longValueExact());
    }

    private void insertOpeningGrant(
            long accountId,
            BigDecimal initialCash,
            LocalDate effectiveBusinessDate,
            String changedBy,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(
                    account_id, flow_type, amount, reason, created_by,
                    corporate_action_id, corporate_action_entitlement_id,
                    effective_business_date, created_at
                ) values (?, 'DEPOSIT', ?, 'OPENING_GRANT', ?, null, null, ?, ?)
                """,
                accountId,
                initialCash,
                changedBy,
                effectiveBusinessDate,
                now
        );
    }

    private void insertPolicyVersion(
            PresetPolicy preset,
            BigDecimal aumRate,
            BigDecimal initialCash,
            LocalDate effectiveBusinessDate,
            String changeReason,
            String changedBy,
            LocalDateTime now
    ) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("preset", "INDEPENDENT_INSTITUTION_PORTFOLIO_V1");
        config.put("portfolioCode", preset.portfolioCode());
        config.put("executionMode", "LIVE");
        config.put("institutionAumRateOfMarketCap", aumRate);
        config.put("initialCash", initialCash);
        config.put("referenceDailyVolumeRate", REFERENCE_VOLUME_RATE);
        config.put("dailyParticipationRate", preset.dailyParticipationRate());
        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Institution preset policy JSON serialization failed", ex);
        }
        jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no, effective_business_date,
                    status, config_json, change_reason, changed_by,
                    created_at, updated_at
                ) values (
                    'INSTITUTIONAL_PORTFOLIO', ?, 1, ?,
                    'SCHEDULED', ?, ?, ?, ?, ?
                )
                """,
                preset.portfolioCode(),
                effectiveBusinessDate,
                configJson,
                changeReason,
                changedBy,
                now,
                now
        );
    }

    private long insertWithGeneratedKey(
            String sql,
            PreparedStatementBinder binder
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );
            binder.bind(statement);
            return statement;
        }, keyHolder);
        if (inserted != 1 || keyHolder.getKey() == null) {
            throw new IllegalStateException("Institution preset insert did not return one generated id");
        }
        return keyHolder.getKey().longValue();
    }

    private PresetPolicy provisioningPolicy(
            String portfolioCode,
            String displayName,
            InstitutionPortfolioPolicyCatalog.Policy style
    ) {
        String keyFragment = portfolioCode.toLowerCase(Locale.ROOT).replace('_', '-');
        return new PresetPolicy(
                portfolioCode,
                "INSTITUTION_" + portfolioCode,
                displayName,
                "stock-institution-" + keyFragment,
                "INST-" + portfolioCode,
                "INSTITUTION:" + portfolioCode,
                portfolioCode,
                style.investmentStyle(),
                style.baseStockAllocationRate(),
                style.minStockAllocationRate(),
                style.maxStockAllocationRate(),
                style.primaryRegimeWeight(),
                style.assetPreferenceSensitivity(),
                style.volatilitySensitivity(),
                style.entryThresholdRate(),
                style.exitThresholdRate(),
                style.dailyTurnoverLimitRate(),
                style.maxDecisionTurnoverRate(),
                style.decisionIntervalMinutes(),
                style.pricePressureSensitivity(),
                style.momentumSensitivity(),
                style.valueSensitivity(),
                style.reportSensitivity(),
                style.dailyParticipationRate()
        );
    }

    private record MarketSymbol(String symbol, long tradableShares, BigDecimal currentPrice) {

        BigDecimal marketCapitalization() {
            return currentPrice.multiply(BigDecimal.valueOf(tradableShares));
        }
    }

    private record PresetPolicy(
            String portfolioCode,
            String participantCode,
            String displayName,
            String userKey,
            String accountCode,
            String selfTradeGroupId,
            String deskCode,
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
            BigDecimal pricePressureSensitivity,
            BigDecimal momentumSensitivity,
            BigDecimal valueSensitivity,
            BigDecimal reportSensitivity,
            BigDecimal dailyParticipationRate
    ) {
    }

    @FunctionalInterface
    private interface PreparedStatementBinder {
        void bind(PreparedStatement statement) throws java.sql.SQLException;
    }
}
