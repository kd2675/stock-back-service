package stock.back.service.market.biz;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private final SimulationMarketSessionService simulationMarketSessionService;
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
                request.priceOffsetTicks()
        );
        ListingAutoAccountConfigValidator.validate(config);
        return toListingAutoAccountResponse(config);
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
        Integer intensity = request == null ? null : request.intensity();
        Integer maxOrderQuantity = request == null ? null : request.maxOrderQuantity();
        Integer orderTtlSeconds = request == null ? null : request.orderTtlSeconds();
        if (intensity != null && (intensity < 1 || intensity > 10)) {
            throw StockException.badRequest("Intensity must be between 1 and 10");
        }
        if (maxOrderQuantity != null && maxOrderQuantity <= 0) {
            throw StockException.badRequest("Max order quantity must be positive");
        }
        if (orderTtlSeconds != null && orderTtlSeconds <= 0) {
            throw StockException.badRequest("Order TTL seconds must be positive");
        }
        config.update(
                request == null ? null : request.enabled(),
                intensity,
                maxOrderQuantity,
                orderTtlSeconds
        );
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
        return new AutoMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getIntensity() == null ? 0 : config.getIntensity(),
                config.getMaxOrderQuantity() == null ? 0 : config.getMaxOrderQuantity(),
                config.getOrderTtlSeconds() == null ? 0 : config.getOrderTtlSeconds(),
                dailyRegime
        );
    }

    private ListingAutoAccountResponse toListingAutoAccountResponse(StockListingAutoAccountConfig config) {
        ListingAutoAccountLedger ledger = listingAutoAccountLedgerQueryService.findLedger(config);
        return new ListingAutoAccountResponse(
                config.getSymbol(),
                config.getUserKey(),
                config.getDisplayName(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getPositionSide(),
                loadIssuedShares(config.getSymbol()),
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
        return new AutoMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getIntensity() == null ? 0 : config.getIntensity(),
                config.getMaxOrderQuantity() == null ? 0 : config.getMaxOrderQuantity(),
                config.getOrderTtlSeconds() == null ? 0 : config.getOrderTtlSeconds(),
                null
        );
    }

    private AutoMarketDailyRegimeResponse regenerateDailyRegimeRow(
            StockAutoMarketConfig config,
            LocalDateTime currentMarketDateTime,
            String regimePhase
    ) {
        long seed = ThreadLocalRandom.current().nextLong();
        Random random = new Random(seed);
        String priceDirection = pickPriceDirection(random.nextInt(100));
        String assetPreference = pickAssetPreference(random.nextInt(100));
        int directionIntensity = Math.clamp(config.getIntensity() == null ? 5 : config.getIntensity(), 1, 10);
        int volatilityLevel = pickBellishLevel(random);
        int liquidityLevel = pickBellishLevel(random);
        int executionAggressionLevel = pickBellishLevel(random);
        int updatedCount = jdbcTemplate.update(
                """
                update stock_order_book_daily_regime
                   set price_direction = ?,
                       asset_preference = ?,
                       direction_intensity = ?,
                       volatility_level = ?,
                       liquidity_level = ?,
                       execution_aggression_level = ?,
                       seed = ?,
                       updated_at = ?
                 where symbol = ?
                   and simulation_trade_date = ?
                   and regime_phase = ?
                """,
                priceDirection,
                assetPreference,
                directionIntensity,
                volatilityLevel,
                liquidityLevel,
                executionAggressionLevel,
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
                    priceDirection,
                    assetPreference,
                    directionIntensity,
                    volatilityLevel,
                    liquidityLevel,
                    executionAggressionLevel,
                    seed
            );
        }
        return new AutoMarketDailyRegimeResponse(
                config.getSymbol(),
                currentMarketDateTime.toLocalDate(),
                regimePhase,
                priceDirection,
                assetPreference,
                directionIntensity,
                volatilityLevel,
                liquidityLevel,
                executionAggressionLevel,
                Long.toString(seed),
                loadCurrentModifier(config.getSymbol(), currentMarketDateTime, regimePhase),
                currentMarketDateTime,
                currentMarketDateTime
        );
    }

    private void insertDailyRegimeRow(
            String symbol,
            LocalDateTime currentMarketDateTime,
            String regimePhase,
            String priceDirection,
            String assetPreference,
            int directionIntensity,
            int volatilityLevel,
            int liquidityLevel,
            int executionAggressionLevel,
            long seed
    ) {
        try {
            jdbcTemplate.update(
                    """
                    insert into stock_order_book_daily_regime(
                        symbol, simulation_trade_date, regime_phase, price_direction, asset_preference,
                        direction_intensity, volatility_level, liquidity_level, execution_aggression_level, seed,
                        created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    symbol,
                    currentMarketDateTime.toLocalDate(),
                    regimePhase,
                    priceDirection,
                    assetPreference,
                    directionIntensity,
                    volatilityLevel,
                    liquidityLevel,
                    executionAggressionLevel,
                    seed,
                    currentMarketDateTime,
                    currentMarketDateTime
            );
        } catch (DuplicateKeyException ignored) {
            jdbcTemplate.update(
                    """
                    update stock_order_book_daily_regime
                       set price_direction = ?,
                           asset_preference = ?,
                           direction_intensity = ?,
                           volatility_level = ?,
                           liquidity_level = ?,
                           execution_aggression_level = ?,
                           seed = ?,
                           updated_at = ?
                     where symbol = ?
                       and simulation_trade_date = ?
                       and regime_phase = ?
                    """,
                    priceDirection,
                    assetPreference,
                    directionIntensity,
                    volatilityLevel,
                    liquidityLevel,
                    executionAggressionLevel,
                    seed,
                    currentMarketDateTime,
                    symbol,
                    currentMarketDateTime.toLocalDate(),
                    regimePhase
            );
        }
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
                               price_direction_modifier,
                               asset_preference_modifier,
                               direction_intensity_modifier,
                               volatility_modifier,
                               liquidity_modifier,
                               execution_aggression_modifier,
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
                                rs.getInt("price_direction_modifier"),
                                rs.getInt("asset_preference_modifier"),
                                rs.getInt("direction_intensity_modifier"),
                                rs.getInt("volatility_modifier"),
                                rs.getInt("liquidity_modifier"),
                                rs.getInt("execution_aggression_modifier"),
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
        if (currentMarketDateTime == null || currentMarketDateTime.toLocalTime().isBefore(midSessionTime())) {
            return "OPENING";
        }
        return "MIDDAY";
    }

    private LocalTime midSessionTime() {
        LocalTime openTime = simulationMarketSessionService.openTime();
        LocalTime closeTime = simulationMarketSessionService.closeTime();
        long halfSessionSeconds = Duration.between(openTime, closeTime).toSeconds() / 2;
        return openTime.plusSeconds(halfSessionSeconds);
    }

    private LocalDateTime modifierWindowStartAt(LocalDateTime now) {
        int minute = now.getMinute() < 30 ? 0 : 30;
        return now.withMinute(minute).withSecond(0).withNano(0);
    }

    private String pickPriceDirection(int roll) {
        if (roll < 43) {
            return "UP";
        }
        if (roll < 86) {
            return "DOWN";
        }
        return "NEUTRAL";
    }

    private String pickAssetPreference(int roll) {
        if (roll < 42) {
            return "STOCK";
        }
        if (roll < 84) {
            return "CASH";
        }
        return "BALANCED";
    }

    private int pickBellishLevel(Random random) {
        int first = random.nextInt(10) + 1;
        int second = random.nextInt(10) + 1;
        return Math.clamp((first + second + 1) / 2, 1, 10);
    }

}
