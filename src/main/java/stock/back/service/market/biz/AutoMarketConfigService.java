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
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.AutoMarketDailyRegimeResponse;
import stock.back.service.market.vo.AutoMarketDistributionBiasRequest;
import stock.back.service.market.vo.AutoMarketDistributionBiasResponse;
import stock.back.service.market.vo.AutoMarketRegimeModifierResponse;
import stock.back.service.market.vo.ListingAutoAccountRequest;
import stock.back.service.market.vo.ListingAutoAccountResponse;

@Service
@RequiredArgsConstructor
public class AutoMarketConfigService {

    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final ListingAutoAccountLedgerQueryService listingAutoAccountLedgerQueryService;
    private final SimulationClockService simulationClockService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public ListingAutoAccountResponse updateListingAutoAccountConfig(String symbol, ListingAutoAccountRequest request) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (request == null) {
            throw StockException.badRequest("Listing auto account update is required");
        }
        StockListingAutoAccountConfig config = stockListingAutoAccountConfigRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Listing auto account not found: " + normalizedSymbol));
        String displayName = request.displayName() == null ? null : MarketTextNormalizer.text(request.displayName());
        if (displayName != null && displayName.length() > 80) {
            throw StockException.badRequest("Listing auto account display name must be 80 characters or less");
        }
        config.update(
                displayName,
                request.enabled(),
                request.positionSide(),
                request.maxOrderQuantity(),
                request.orderTtlSeconds(),
                request.priceOffsetTicks(),
                request.targetBuyQuantity(),
                request.targetSellQuantity(),
                request.targetHoldingQuantity(),
                request.inventoryBandQuantity(),
                request.buyPriceOffsetDirection(),
                request.sellPriceOffsetDirection()
        );
        long issuedShares = loadIssuedShares(config.getSymbol());
        ListingAutoAccountConfigValidator.validate(config, issuedShares);
        return toListingAutoAccountResponse(config, issuedShares);
    }

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
                .orElseGet(() -> StockAutoMarketConfig.defaults(normalizedSymbol));
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
            validateDistributionBias(request.primaryDistributionBias(), "Primary");
            validateDistributionBias(request.secondaryDistributionBias(), "Secondary");
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
                .orElseGet(() -> StockAutoMarketConfig.defaults(normalizedSymbol));
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
                .orElseGet(() -> StockAutoMarketConfig.defaults(normalizedSymbol));
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
                new AutoMarketDailyRegimeResponse(
                        dailyRegime.symbol(),
                        dailyRegime.simulationTradeDate(),
                        dailyRegime.regimePhase(),
                        dailyRegime.pricePressure(),
                        dailyRegime.assetPreferencePressure(),
                        dailyRegime.volatilityPressure(),
                        dailyRegime.liquidityPressure(),
                        dailyRegime.executionAggressionPressure(),
                        dailyRegime.seed(),
                        modifier,
                        dailyRegime.createdAt(),
                        dailyRegime.updatedAt()
                )
        );
    }

    private ListingAutoAccountResponse toListingAutoAccountResponse(
            StockListingAutoAccountConfig config,
            long issuedShares
    ) {
        ListingAutoAccountLedger ledger = listingAutoAccountLedgerQueryService.findLedger(config);
        return new ListingAutoAccountResponse(
                config.getSymbol(),
                config.getUserKey(),
                config.getDisplayName(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getPositionSide(),
                issuedShares,
                ledger.accountId(),
                ledger.cashBalance(),
                ledger.holdingQuantity(),
                ledger.reservedQuantity(),
                ledger.availableQuantity(),
                ledger.averagePrice(),
                ledger.currentPrice(),
                ledger.marketValue(),
                config.getMaxOrderQuantity(),
                config.getOrderTtlSeconds(),
                config.getPriceOffsetTicks(),
                config.getTargetBuyQuantity(),
                config.getTargetSellQuantity(),
                config.getTargetHoldingQuantity(),
                config.getInventoryBandQuantity(),
                ledger.openBuyQuantity(),
                ledger.openSellQuantity(),
                config.getBuyPriceOffsetDirection(),
                config.getSellPriceOffsetDirection(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private long loadIssuedShares(String symbol) {
        return stockOrderBookInstrumentRepository.findById(symbol)
                .map(instrument -> instrument.getIssuedShares() == null ? 0L : instrument.getIssuedShares())
                .orElse(0L);
    }

    private AutoMarketConfigResponse toAutoMarketConfigResponse(StockAutoMarketConfig config) {
        return toAutoMarketConfigResponse(config, null);
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
                currentMarketDateTime,
                config.getSymbol(),
                currentMarketDateTime.toLocalDate(),
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
        return new AutoMarketDailyRegimeResponse(
                config.getSymbol(),
                currentMarketDateTime.toLocalDate(),
                regimePhase,
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
                        symbol, simulation_trade_date, regime_phase, price_pressure, asset_preference_pressure,
                        volatility_pressure, liquidity_pressure, execution_aggression_pressure, seed,
                        created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    symbol,
                    currentMarketDateTime.toLocalDate(),
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
                               price_pressure,
                               asset_preference_pressure,
                               volatility_pressure,
                               liquidity_pressure,
                               execution_aggression_pressure,
                               seed,
                               created_at,
                               updated_at
                         from stock_order_book_daily_regime
                         where symbol = ?
                           and simulation_trade_date = ?
                           and regime_phase = ?
                        """,
                        (rs, rowNum) -> new AutoMarketDailyRegimeResponse(
                                rs.getString("symbol"),
                                rs.getDate("simulation_trade_date").toLocalDate(),
                                rs.getString("regime_phase"),
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
