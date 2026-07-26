package stock.back.service.market.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.InstitutionScaledPresetRequest;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

@Service
public class InstitutionScaledPresetService {

    private static final BigDecimal DEFAULT_AUM_RATE = new BigDecimal("0.010000");
    private static final BigDecimal MIN_AUM_RATE = new BigDecimal("0.001000");
    private static final BigDecimal MAX_AUM_RATE = new BigDecimal("0.020000");
    private static final BigDecimal REFERENCE_VOLUME_RATE = new BigDecimal("0.030000");
    private static final Set<String> PRESET_CODES = Set.of(
            "INST_PENSION",
            "INST_VALUE",
            "INST_MOMENTUM",
            "INST_ACTIVE"
    );

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService marketSessionService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    public InstitutionScaledPresetService(
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
    public void createScaledDefaults(
            InstitutionScaledPresetRequest request,
            String changedBy
    ) {
        BigDecimal aumRate = normalizeAumRate(request);
        String changeReason = normalizeChangeReason(request);
        String normalizedChangedBy = normalizeChangedBy(changedBy);
        List<String> existingCodes = jdbcClient.sql(
                        """
                        select portfolio_code
                          from stock_institution_portfolio
                         where portfolio_code in (:portfolioCodes)
                         order by portfolio_code asc
                         for update
                        """
                )
                .param("portfolioCodes", PRESET_CODES)
                .query(String.class)
                .list();
        if (existingCodes.size() == PRESET_CODES.size()) {
            return;
        }
        if (!existingCodes.isEmpty()) {
            throw StockException.conflict(
                    "Scaled institution preset is partially provisioned; repair the existing set first: "
                            + String.join(",", existingCodes)
            );
        }

        SimulationClockSnapshot clock = requirePausedPreOpen();
        LocalDate activeBusinessDate = marketLedgerFreezeGuard.acquireMutationPermit(
                "scaled institution preset creation"
        );
        if (!activeBusinessDate.equals(clock.simulationDate())) {
            throw StockException.conflict(
                    "Simulation date and active market business date must match"
            );
        }
        List<MarketSymbol> symbols = findActiveMarketSymbols();
        if (symbols.isEmpty()) {
            throw StockException.conflict(
                    "At least one active order-book symbol with a positive price is required"
            );
        }
        BigDecimal marketCapitalization = symbols.stream()
                .map(MarketSymbol::marketCapitalization)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal initialCash = marketCapitalization.multiply(aumRate)
                .setScale(2, RoundingMode.DOWN);
        if (initialCash.signum() <= 0) {
            throw StockException.badRequest("Scaled institution initial AUM must be positive");
        }
        LocalDateTime now = clock.simulationDateTime();
        LocalDate firstDecisionDate = activeBusinessDate.plusDays(1);
        LocalDateTime firstDecisionAt = firstDecisionDate.atTime(marketSessionService.openTime());
        Map<String, BigDecimal> marketWeights = marketWeights(symbols, marketCapitalization);

        for (PresetPolicy preset : presets()) {
            long participantId = insertParticipant(preset, now);
            long accountId = insertAccount(preset, initialCash, now);
            insertParticipantAccount(
                    participantId,
                    accountId,
                    preset,
                    firstDecisionDate,
                    now
            );
            long portfolioId = insertPortfolio(
                    participantId,
                    accountId,
                    preset,
                    firstDecisionAt,
                    now
            );
            insertMandates(
                    portfolioId,
                    preset,
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
                    preset,
                    aumRate,
                    initialCash,
                    firstDecisionDate,
                    changeReason,
                    normalizedChangedBy,
                    now
            );
        }
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
                    "Scaled institution presets can only be created during a paused pre-open"
            );
        }
        return clock;
    }

    private BigDecimal normalizeAumRate(InstitutionScaledPresetRequest request) {
        BigDecimal rate = request == null || request.institutionAumRateOfMarketCap() == null
                ? DEFAULT_AUM_RATE
                : request.institutionAumRateOfMarketCap();
        if (rate.compareTo(MIN_AUM_RATE) < 0 || rate.compareTo(MAX_AUM_RATE) > 0) {
            throw StockException.badRequest(
                    "Institution AUM rate must be between 0.001 and 0.020 per portfolio"
            );
        }
        return rate.setScale(6, RoundingMode.HALF_UP);
    }

    private String normalizeChangeReason(InstitutionScaledPresetRequest request) {
        String reason = request == null ? null : request.changeReason();
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) {
            return "Create scaled four-institution shadow preset";
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
                    ?, ?, ?, ?, ?, 'SHADOW', 'ACTIVE',
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
        config.put("preset", "SCALED_FOUR_INSTITUTIONS_V1");
        config.put("portfolioCode", preset.portfolioCode());
        config.put("executionMode", "SHADOW");
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

    private List<PresetPolicy> presets() {
        return List.of(
                new PresetPolicy(
                        "INST_PENSION", "INSTITUTION_PENSION", "축소 연기금 균형형",
                        "stock-institution-pension", "INST-PENSION",
                        "INSTITUTION:PENSION", "BALANCED", "BALANCED_LONG_TERM",
                        decimal("0.600000"), decimal("0.500000"), decimal("0.700000"),
                        decimal("0.800000"), decimal("0.015000"), decimal("0.020000"),
                        decimal("0.005000"), decimal("0.002000"),
                        decimal("0.005000"), decimal("0.001000"), 120,
                        decimal("0.020000"), decimal("0.020000"),
                        decimal("0.020000"), decimal("0.020000"), decimal("0.010000")
                ),
                new PresetPolicy(
                        "INST_VALUE", "INSTITUTION_VALUE", "축소 가치 역추세형",
                        "stock-institution-value", "INST-VALUE",
                        "INSTITUTION:VALUE", "VALUE", "VALUE_CONTRARIAN",
                        decimal("0.650000"), decimal("0.450000"), decimal("0.800000"),
                        decimal("0.750000"), decimal("0.020000"), decimal("0.020000"),
                        decimal("0.005000"), decimal("0.002000"),
                        decimal("0.010000"), decimal("0.002000"), 90,
                        decimal("-0.120000"), decimal("-0.020000"),
                        decimal("0.250000"), decimal("0.080000"), decimal("0.020000")
                ),
                new PresetPolicy(
                        "INST_MOMENTUM", "INSTITUTION_MOMENTUM", "축소 모멘텀형",
                        "stock-institution-momentum", "INST-MOMENTUM",
                        "INSTITUTION:MOMENTUM", "MOMENTUM", "MOMENTUM",
                        decimal("0.600000"), decimal("0.350000"), decimal("0.850000"),
                        decimal("0.600000"), decimal("0.025000"), decimal("0.025000"),
                        decimal("0.006000"), decimal("0.002500"),
                        decimal("0.015000"), decimal("0.003000"), 60,
                        decimal("0.150000"), decimal("0.250000"),
                        decimal("-0.030000"), decimal("0.100000"), decimal("0.020000")
                ),
                new PresetPolicy(
                        "INST_ACTIVE", "INSTITUTION_ACTIVE", "축소 단기 적극형",
                        "stock-institution-active", "INST-ACTIVE",
                        "INSTITUTION:ACTIVE", "ACTIVE", "ACTIVE_SHORT_TERM",
                        decimal("0.550000"), decimal("0.250000"), decimal("0.850000"),
                        decimal("0.400000"), decimal("0.030000"), decimal("0.030000"),
                        decimal("0.008000"), decimal("0.003000"),
                        decimal("0.020000"), decimal("0.004000"), 30,
                        decimal("0.200000"), decimal("0.200000"),
                        decimal("0.000000"), decimal("0.120000"), decimal("0.030000")
                )
        );
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
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
