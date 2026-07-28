package stock.back.service.market.biz;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.AutoMarketDailyRegimeResponse;
import stock.back.service.market.vo.AutoMarketDistributionBiasRequest;
import stock.back.service.market.vo.AutoMarketDistributionBiasResponse;
import stock.back.service.market.vo.AutoMarketRegimeCountWeightsRequest;
import stock.back.service.market.vo.AutoMarketRegimeCountWeightsResponse;
import stock.back.service.market.vo.AutoMarketRegimeModifierResponse;

@Service
@RequiredArgsConstructor
public class AutoMarketConfigService {

    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final SimulationClockService simulationClockService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public AutoMarketConfigResponse updateAutoMarketConfig(String symbol, AutoMarketConfigUpdateRequest request) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        StockAutoMarketConfig config = stockAutoMarketConfigRepository.findById(normalizedSymbol)
                .orElseGet(() -> recommendedDefaults(normalizedSymbol));
        Integer maxOrderQuantity = request == null ? null : request.maxOrderQuantity();
        Integer orderTtlSeconds = request == null ? null : request.orderTtlSeconds();
        if (maxOrderQuantity != null && maxOrderQuantity <= 0) {
            throw StockException.badRequest("Max order quantity must be positive");
        }
        if (orderTtlSeconds != null && orderTtlSeconds <= 0) {
            throw StockException.badRequest("Order TTL seconds must be positive");
        }
        config.update(
                request == null ? null : request.enabled(),
                maxOrderQuantity,
                orderTtlSeconds
        );
        if (request != null) {
            validateRegimeCountWeights(request.primaryRegimeCountWeights());
            validateDistributionBias(request.primaryDistributionBias(), "Primary");
            validateDistributionBias(request.secondaryDistributionBias(), "Secondary");
            updatePrimaryRegimeCountWeights(config, request.primaryRegimeCountWeights());
            updatePrimaryDistributionBias(config, request.primaryDistributionBias());
            updateSecondaryDistributionBias(config, request.secondaryDistributionBias());
        }
        return toAutoMarketConfigResponse(stockAutoMarketConfigRepository.save(config));
    }

    @Transactional
    public AutoMarketConfigResponse regenerateDailyRegime(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        StockAutoMarketConfig config = stockAutoMarketConfigRepository.findById(normalizedSymbol)
                .orElseGet(() -> recommendedDefaults(normalizedSymbol));
        LocalDateTime currentMarketDateTime = simulationClockService.currentMarketDateTime();
        String regimePhase = resolveRegimePhase(currentMarketDateTime);
        AutoMarketDailyRegimeResponse dailyRegime = regenerateDailyRegimeRow(config, currentMarketDateTime, regimePhase);
        return toAutoMarketConfigResponse(config, dailyRegime);
    }

    @Transactional
    public AutoMarketConfigResponse regenerateRegimeModifier(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        StockAutoMarketConfig config = stockAutoMarketConfigRepository.findById(normalizedSymbol)
                .orElseGet(() -> recommendedDefaults(normalizedSymbol));
        LocalDateTime currentMarketDateTime = simulationClockService.currentMarketDateTime();
        String regimePhase = resolveRegimePhase(currentMarketDateTime);
        AutoMarketDailyRegimeResponse dailyRegime = loadDailyRegime(config.getSymbol(), currentMarketDateTime, regimePhase);
        if (dailyRegime == null) {
            throw StockException.badRequest("Daily regime is required before regenerating a modifier");
        }
        AutoMarketRegimeModifierResponse modifier = regenerateRegimeModifierRow(
                config,
                currentMarketDateTime,
                regimePhase,
                modifierWindowStartAt(currentMarketDateTime)
        );
        return toAutoMarketConfigResponse(
                config,
                dailyRegime.withCurrentModifier(modifier)
        );
    }

    private AutoMarketConfigResponse toAutoMarketConfigResponse(StockAutoMarketConfig config) {
        return toAutoMarketConfigResponse(config, null);
    }

    private StockAutoMarketConfig recommendedDefaults(String symbol) {
        StockOrderBookInstrument instrument =
                stockOrderBookInstrumentRepository.findById(symbol)
                        .orElseThrow(() -> StockException.notFound(
                                "Unknown order book symbol: " + symbol
                        ));
        int maxOrderQuantity =
                AutoMarketOrderQuantityLimitPolicy.recommendedMaxOrderQuantity(
                        instrument.getInitialPrice(),
                        instrument.getTradableShares()
                );
        return StockAutoMarketConfig.defaults(symbol, maxOrderQuantity);
    }

    private AutoMarketConfigResponse toAutoMarketConfigResponse(
            StockAutoMarketConfig config,
            AutoMarketDailyRegimeResponse dailyRegime
    ) {
        return new AutoMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getMaxOrderQuantity() == null ? 0 : config.getMaxOrderQuantity(),
                config.getOrderTtlSeconds() == null ? 0 : config.getOrderTtlSeconds(),
                primaryRegimeCountWeights(config),
                primaryDistributionBias(config),
                secondaryDistributionBias(config),
                dailyRegime
        );
    }

    private AutoMarketDailyRegimeResponse regenerateDailyRegimeRow(
            StockAutoMarketConfig config,
            LocalDateTime currentMarketDateTime,
            String regimePhase
    ) {
        AutoMarketDailyRegimeResponse existing = loadDailyRegime(
                config.getSymbol(),
                currentMarketDateTime,
                regimePhase
        );
        String previousSourcePhase = existing == null ? regimePhase : existing.sourceRegimePhase();
        long seed = ThreadLocalRandom.current().nextLong();
        Random random = new Random(seed);
        AutoMarketDistributionBiasResponse bias = primaryDistributionBias(config);
        int pricePressure = AutoMarketPressureSampler.sample(random, bias.pricePressure());
        int assetPreferencePressure = AutoMarketPressureSampler.sample(random, bias.assetPreferencePressure());
        int volatilityPressure = AutoMarketPressureSampler.sample(random, bias.volatilityPressure());
        int liquidityPressure = AutoMarketPressureSampler.sample(random, bias.liquidityPressure());
        int executionAggressionPressure = AutoMarketPressureSampler.sample(random, bias.executionAggressionPressure());
        int updatedCount = jdbcTemplate.update(
                """
                update stock_order_book_daily_regime
                   set price_pressure = ?,
                       asset_preference_pressure = ?,
                       volatility_pressure = ?,
                       liquidity_pressure = ?,
                       execution_aggression_pressure = ?,
                       seed = ?,
                       source_regime_phase = ?,
                       updated_at = ?
                 where symbol = ?
                   and simulation_trade_date = ?
                   and (
                       regime_phase = ?
                       or (
                           coalesce(source_regime_phase, regime_phase) = ?
                           and case regime_phase
                               when 'SLOT_0600' then 1
                               when 'SLOT_0900' then 2
                               when 'SLOT_1200' then 3
                               when 'SLOT_1500' then 4
                               else 0
                           end >= case ?
                               when 'SLOT_0600' then 1
                               when 'SLOT_0900' then 2
                               when 'SLOT_1200' then 3
                               when 'SLOT_1500' then 4
                               else 5
                           end
                       )
                   )
                """,
                pricePressure,
                assetPreferencePressure,
                volatilityPressure,
                liquidityPressure,
                executionAggressionPressure,
                seed,
                regimePhase,
                currentMarketDateTime,
                config.getSymbol(),
                currentMarketDateTime.toLocalDate(),
                regimePhase,
                previousSourcePhase,
                regimePhase
        );
        if (updatedCount == 0) {
            insertDailyRegimeRow(
                    config.getSymbol(),
                    currentMarketDateTime,
                    regimePhase,
                    pricePressure,
                    assetPreferencePressure,
                    volatilityPressure,
                    liquidityPressure,
                    executionAggressionPressure,
                    seed
            );
        }
        AutoMarketDailyRegimeResponse regenerated = loadDailyRegime(
                config.getSymbol(),
                currentMarketDateTime,
                regimePhase
        );
        if (regenerated != null) {
            return regenerated.withCurrentModifier(loadCurrentModifier(
                    config.getSymbol(),
                    currentMarketDateTime,
                    regimePhase
            ));
        }
        return new AutoMarketDailyRegimeResponse(
                config.getSymbol(),
                currentMarketDateTime.toLocalDate(),
                regimePhase,
                regimePhase,
                1,
                1,
                pricePressure,
                assetPreferencePressure,
                volatilityPressure,
                liquidityPressure,
                executionAggressionPressure,
                Long.toString(seed),
                loadCurrentModifier(config.getSymbol(), currentMarketDateTime, regimePhase),
                currentMarketDateTime,
                currentMarketDateTime
        );
    }

    private AutoMarketRegimeModifierResponse regenerateRegimeModifierRow(
            StockAutoMarketConfig config,
            LocalDateTime currentMarketDateTime,
            String regimePhase,
            LocalDateTime modifierWindowStartAt
    ) {
        long seed = ThreadLocalRandom.current().nextLong();
        Random random = new Random(seed);
        AutoMarketDistributionBiasResponse bias = secondaryDistributionBias(config);
        int pricePressure = AutoMarketPressureSampler.sample(random, bias.pricePressure());
        int assetPreferencePressure = AutoMarketPressureSampler.sample(random, bias.assetPreferencePressure());
        int volatilityPressure = AutoMarketPressureSampler.sample(random, bias.volatilityPressure());
        int liquidityPressure = AutoMarketPressureSampler.sample(random, bias.liquidityPressure());
        int executionAggressionPressure = AutoMarketPressureSampler.sample(random, bias.executionAggressionPressure());
        int updatedCount = updateRegimeModifierRow(
                config.getSymbol(),
                currentMarketDateTime,
                regimePhase,
                modifierWindowStartAt,
                pricePressure,
                assetPreferencePressure,
                volatilityPressure,
                liquidityPressure,
                executionAggressionPressure,
                seed
        );
        if (updatedCount == 0) {
            insertRegimeModifierRow(
                    config.getSymbol(),
                    currentMarketDateTime,
                    regimePhase,
                    modifierWindowStartAt,
                    pricePressure,
                    assetPreferencePressure,
                    volatilityPressure,
                    liquidityPressure,
                    executionAggressionPressure,
                    seed
            );
        }
        return new AutoMarketRegimeModifierResponse(
                modifierWindowStartAt,
                pricePressure,
                assetPreferencePressure,
                volatilityPressure,
                liquidityPressure,
                executionAggressionPressure,
                Long.toString(seed),
                currentMarketDateTime,
                currentMarketDateTime
        );
    }

    private void insertDailyRegimeRow(
            String symbol,
            LocalDateTime currentMarketDateTime,
            String regimePhase,
            int pricePressure,
            int assetPreferencePressure,
            int volatilityPressure,
            int liquidityPressure,
            int executionAggressionPressure,
            long seed
    ) {
        try {
            jdbcTemplate.update(
                    """
                    insert into stock_order_book_daily_regime(
                        symbol, simulation_trade_date, regime_phase, source_regime_phase,
                        price_pressure, asset_preference_pressure,
                        volatility_pressure, liquidity_pressure, execution_aggression_pressure, seed,
                        created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    symbol,
                    currentMarketDateTime.toLocalDate(),
                    regimePhase,
                    regimePhase,
                    pricePressure,
                    assetPreferencePressure,
                    volatilityPressure,
                    liquidityPressure,
                    executionAggressionPressure,
                    seed,
                    currentMarketDateTime,
                    currentMarketDateTime
            );
        } catch (DuplicateKeyException ignored) {
            jdbcTemplate.update(
                    """
                    update stock_order_book_daily_regime
                       set price_pressure = ?,
                           asset_preference_pressure = ?,
                           volatility_pressure = ?,
                           liquidity_pressure = ?,
                           execution_aggression_pressure = ?,
                           seed = ?,
                           source_regime_phase = ?,
                           updated_at = ?
                     where symbol = ?
                       and simulation_trade_date = ?
                       and regime_phase = ?
                    """,
                    pricePressure,
                    assetPreferencePressure,
                    volatilityPressure,
                    liquidityPressure,
                    executionAggressionPressure,
                    seed,
                    regimePhase,
                    currentMarketDateTime,
                    symbol,
                    currentMarketDateTime.toLocalDate(),
                    regimePhase
            );
        }
    }

    private int updateRegimeModifierRow(
            String symbol,
            LocalDateTime currentMarketDateTime,
            String regimePhase,
            LocalDateTime modifierWindowStartAt,
            int pricePressure,
            int assetPreferencePressure,
            int volatilityPressure,
            int liquidityPressure,
            int executionAggressionPressure,
            long seed
    ) {
        return jdbcTemplate.update(
                """
                update stock_order_book_regime_modifier
                   set price_pressure = ?,
                       asset_preference_pressure = ?,
                       volatility_pressure = ?,
                       liquidity_pressure = ?,
                       execution_aggression_pressure = ?,
                       seed = ?,
                       updated_at = ?
                 where symbol = ?
                   and simulation_trade_date = ?
                   and regime_phase = ?
                   and modifier_window_start_at = ?
                """,
                pricePressure,
                assetPreferencePressure,
                volatilityPressure,
                liquidityPressure,
                executionAggressionPressure,
                seed,
                currentMarketDateTime,
                symbol,
                currentMarketDateTime.toLocalDate(),
                regimePhase,
                modifierWindowStartAt
        );
    }

    private void insertRegimeModifierRow(
            String symbol,
            LocalDateTime currentMarketDateTime,
            String regimePhase,
            LocalDateTime modifierWindowStartAt,
            int pricePressure,
            int assetPreferencePressure,
            int volatilityPressure,
            int liquidityPressure,
            int executionAggressionPressure,
            long seed
    ) {
        try {
            jdbcTemplate.update(
                    """
                    insert into stock_order_book_regime_modifier(
                        symbol, simulation_trade_date, regime_phase, modifier_window_start_at,
                        price_pressure, asset_preference_pressure, volatility_pressure,
                        liquidity_pressure, execution_aggression_pressure,
                        seed, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    symbol,
                    currentMarketDateTime.toLocalDate(),
                    regimePhase,
                    modifierWindowStartAt,
                    pricePressure,
                    assetPreferencePressure,
                    volatilityPressure,
                    liquidityPressure,
                    executionAggressionPressure,
                    seed,
                    currentMarketDateTime,
                    currentMarketDateTime
            );
        } catch (DuplicateKeyException ignored) {
            updateRegimeModifierRow(
                    symbol,
                    currentMarketDateTime,
                    regimePhase,
                    modifierWindowStartAt,
                    pricePressure,
                    assetPreferencePressure,
                    volatilityPressure,
                    liquidityPressure,
                    executionAggressionPressure,
                    seed
            );
        }
    }

    private AutoMarketDailyRegimeResponse loadDailyRegime(
            String symbol,
            LocalDateTime currentMarketDateTime,
            String regimePhase
    ) {
        return jdbcTemplate.query(
                        """
                        select symbol,
                               simulation_trade_date,
                               regime_phase,
                               coalesce(source_regime_phase, regime_phase) as source_regime_phase,
                               (
                                   select count(*)
                                     from stock_order_book_daily_regime prepared
                                    where prepared.symbol = current_regime.symbol
                                      and prepared.simulation_trade_date = current_regime.simulation_trade_date
                               ) as prepared_regime_slot_count,
                               (
                                   select count(*)
                                     from stock_order_book_daily_regime applied
                                    where applied.symbol = current_regime.symbol
                                      and applied.simulation_trade_date = current_regime.simulation_trade_date
                                      and coalesce(applied.source_regime_phase, applied.regime_phase) = applied.regime_phase
                               ) as daily_application_count,
                               price_pressure,
                               asset_preference_pressure,
                               volatility_pressure,
                               liquidity_pressure,
                               execution_aggression_pressure,
                               seed,
                               created_at,
                               updated_at
                         from stock_order_book_daily_regime current_regime
                         where current_regime.symbol = ?
                           and current_regime.simulation_trade_date = ?
                           and current_regime.regime_phase = ?
                        """,
                        (rs, rowNum) -> new AutoMarketDailyRegimeResponse(
                                rs.getString("symbol"),
                                rs.getDate("simulation_trade_date").toLocalDate(),
                                rs.getString("regime_phase"),
                                rs.getString("source_regime_phase"),
                                rs.getInt("daily_application_count"),
                                rs.getInt("prepared_regime_slot_count"),
                                rs.getInt("price_pressure"),
                                rs.getInt("asset_preference_pressure"),
                                rs.getInt("volatility_pressure"),
                                rs.getInt("liquidity_pressure"),
                                rs.getInt("execution_aggression_pressure"),
                                Long.toString(rs.getLong("seed")),
                                null,
                                rs.getObject("created_at", LocalDateTime.class),
                                rs.getObject("updated_at", LocalDateTime.class)
                        ),
                        symbol,
                        currentMarketDateTime.toLocalDate(),
                        regimePhase
                )
                .stream()
                .findFirst()
                .orElse(null);
    }

    private AutoMarketRegimeModifierResponse loadCurrentModifier(
            String symbol,
            LocalDateTime currentMarketDateTime,
            String regimePhase
    ) {
        LocalDateTime modifierWindowStartAt = modifierWindowStartAt(currentMarketDateTime);
        return jdbcTemplate.query(
                        """
                        select modifier_window_start_at,
                               price_pressure,
                               asset_preference_pressure,
                               volatility_pressure,
                               liquidity_pressure,
                               execution_aggression_pressure,
                               seed,
                               created_at,
                               updated_at
                         from stock_order_book_regime_modifier
                         where symbol = ?
                           and simulation_trade_date = ?
                           and regime_phase = ?
                           and modifier_window_start_at = ?
                        """,
                        (rs, rowNum) -> new AutoMarketRegimeModifierResponse(
                                rs.getObject("modifier_window_start_at", LocalDateTime.class),
                                rs.getInt("price_pressure"),
                                rs.getInt("asset_preference_pressure"),
                                rs.getInt("volatility_pressure"),
                                rs.getInt("liquidity_pressure"),
                                rs.getInt("execution_aggression_pressure"),
                                Long.toString(rs.getLong("seed")),
                                rs.getObject("created_at", LocalDateTime.class),
                                rs.getObject("updated_at", LocalDateTime.class)
                        ),
                        symbol,
                        currentMarketDateTime.toLocalDate(),
                        regimePhase,
                        modifierWindowStartAt
                )
                .stream()
                .findFirst()
                .orElse(null);
    }

    private String resolveRegimePhase(LocalDateTime currentMarketDateTime) {
        return AutoMarketRegimePhaseResolver.resolve(currentMarketDateTime);
    }

    private LocalDateTime modifierWindowStartAt(LocalDateTime now) {
        int minute = now.getMinute() < 30 ? 0 : 30;
        return now.withMinute(minute).withSecond(0).withNano(0);
    }

    private void validateDistributionBias(AutoMarketDistributionBiasRequest bias, String label) {
        if (bias == null) {
            return;
        }
        validateBiasValue(bias.pricePressure(), label + " price pressure bias");
        validateBiasValue(bias.assetPreferencePressure(), label + " asset preference pressure bias");
        validateBiasValue(bias.volatilityPressure(), label + " volatility pressure bias");
        validateBiasValue(bias.liquidityPressure(), label + " liquidity pressure bias");
        validateBiasValue(bias.executionAggressionPressure(), label + " execution aggression pressure bias");
    }

    private void validateRegimeCountWeights(
            AutoMarketRegimeCountWeightsRequest weights
    ) {
        if (weights == null) {
            return;
        }
        validateWeightValue(weights.oneTime(), "One-time regime weight");
        validateWeightValue(weights.twoTimes(), "Two-times regime weight");
        validateWeightValue(weights.threeTimes(), "Three-times regime weight");
        validateWeightValue(weights.fourTimes(), "Four-times regime weight");
    }

    private void validateWeightValue(Integer value, String label) {
        if (value != null && (value < 0 || value > 100)) {
            throw StockException.badRequest(label + " must be between 0 and 100");
        }
    }

    private int valueOrCurrent(Integer value, int current) {
        return value == null ? current : value;
    }

    private void validateBiasValue(Integer value, String label) {
        if (value != null && (value < -100 || value > 100)) {
            throw StockException.badRequest(label + " must be between -100 and 100");
        }
    }

    private void updatePrimaryDistributionBias(StockAutoMarketConfig config, AutoMarketDistributionBiasRequest bias) {
        if (bias == null) {
            return;
        }
        config.updatePrimaryDistributionBias(
                bias.pricePressure(),
                bias.assetPreferencePressure(),
                bias.volatilityPressure(),
                bias.liquidityPressure(),
                bias.executionAggressionPressure()
        );
    }

    private void updatePrimaryRegimeCountWeights(
            StockAutoMarketConfig config,
            AutoMarketRegimeCountWeightsRequest weights
    ) {
        if (weights == null) {
            return;
        }
        int oneTime = valueOrCurrent(
                weights.oneTime(),
                config.getPrimaryRegimeCount1Weight() == null ? 0 : config.getPrimaryRegimeCount1Weight()
        );
        int twoTimes = valueOrCurrent(
                weights.twoTimes(),
                config.getPrimaryRegimeCount2Weight() == null ? 0 : config.getPrimaryRegimeCount2Weight()
        );
        int threeTimes = valueOrCurrent(
                weights.threeTimes(),
                config.getPrimaryRegimeCount3Weight() == null ? 0 : config.getPrimaryRegimeCount3Weight()
        );
        int fourTimes = valueOrCurrent(
                weights.fourTimes(),
                config.getPrimaryRegimeCount4Weight() == null ? 100 : config.getPrimaryRegimeCount4Weight()
        );
        if (oneTime + twoTimes + threeTimes + fourTimes <= 0) {
            throw StockException.badRequest("At least one primary regime count weight must be positive");
        }
        config.updatePrimaryRegimeCountWeights(oneTime, twoTimes, threeTimes, fourTimes);
    }

    private AutoMarketRegimeCountWeightsResponse primaryRegimeCountWeights(
            StockAutoMarketConfig config
    ) {
        return AutoMarketStatusResponseMapper.primaryRegimeCountWeights(config);
    }

    private void updateSecondaryDistributionBias(StockAutoMarketConfig config, AutoMarketDistributionBiasRequest bias) {
        if (bias == null) {
            return;
        }
        config.updateSecondaryDistributionBias(
                bias.pricePressure(),
                bias.assetPreferencePressure(),
                bias.volatilityPressure(),
                bias.liquidityPressure(),
                bias.executionAggressionPressure()
        );
    }

    private AutoMarketDistributionBiasResponse primaryDistributionBias(StockAutoMarketConfig config) {
        return AutoMarketStatusResponseMapper.primaryDistributionBias(config);
    }

    private AutoMarketDistributionBiasResponse secondaryDistributionBias(StockAutoMarketConfig config) {
        return AutoMarketStatusResponseMapper.secondaryDistributionBias(config);
    }

}
