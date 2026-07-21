package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.ListingAutoAccountRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;
import stock.back.service.market.vo.OrderBookInstrumentTradingRulesRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class OrderBookInstrumentCommandService {

    private static final String LISTING_SUPPLY_USER_KEY_PREFIX = "stock-listing-";

    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
    private final StockCorporateActionRepository stockCorporateActionRepository;
    private final StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;

    public OrderBookInstrumentCommandService(
            StockInstrumentRepository stockInstrumentRepository,
            StockPriceRepository stockPriceRepository,
            StockAutoMarketConfigRepository stockAutoMarketConfigRepository,
            StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository,
            StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository,
            StockCorporateActionRepository stockCorporateActionRepository,
            StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository,
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService,
            MarketLedgerFreezeGuard marketLedgerFreezeGuard
    ) {
        this.stockInstrumentRepository = stockInstrumentRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.stockAutoMarketConfigRepository = stockAutoMarketConfigRepository;
        this.stockOrderBookInstrumentRepository = stockOrderBookInstrumentRepository;
        this.stockOrderBookMarketConfigRepository = stockOrderBookMarketConfigRepository;
        this.stockCorporateActionRepository = stockCorporateActionRepository;
        this.stockListingAutoAccountConfigRepository = stockListingAutoAccountConfigRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationClockService = simulationClockService;
        this.marketLedgerFreezeGuard = marketLedgerFreezeGuard;
    }

    @Transactional
    public OrderBookInstrumentResponse createOrderBookInstrument(OrderBookInstrumentRequest request) {
        String symbol = MarketTextNormalizer.symbol(request == null ? null : request.symbol());
        String name = MarketTextNormalizer.text(request == null ? null : request.name());
        String market = MarketTextNormalizer.text(request == null ? null : request.market());
        if (symbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (name.isBlank()) {
            throw StockException.badRequest("Name is required");
        }
        if (market.isBlank()) {
            market = "ORDERBOOK";
        }
        marketLedgerFreezeGuard.acquireMutationPermit("order-book instrument listing");
        validateInstrumentRequest(symbol, name, market, request);
        if (stockInstrumentRepository.existsById(symbol)) {
            throw StockException.conflict("Symbol already exists in virtual price market: " + symbol);
        }
        if (stockOrderBookInstrumentRepository.existsById(symbol)) {
            throw StockException.conflict("Order book symbol already exists: " + symbol);
        }

        BigDecimal tickSize = KoreanStockTickSizePolicy.tickSizeForCurrentPrice(market, request.initialPrice());
        BigDecimal priceLimitRate = request.priceLimitRate() == null ? BigDecimal.valueOf(30) : request.priceLimitRate();
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.save(
                StockOrderBookInstrument.listed(symbol, name, market, request.initialPrice(), request.issuedShares(), tickSize, priceLimitRate, now)
        );
        stockCorporateActionRepository.save(
                StockCorporateAction.initialIssue(symbol, request.issuedShares(), request.initialPrice(), now)
        );
        stockOrderBookMarketConfigRepository.save(StockOrderBookMarketConfig.enabled(symbol, now));
        stockAutoMarketConfigRepository.save(StockAutoMarketConfig.defaults(symbol, now));
        stockPriceRepository.save(StockPrice.initial(symbol, request.initialPrice(), now));
        seedListingAutoAccount(symbol, name, request.initialPrice(), request.issuedShares(), request.listingAutoAccount(), now);
        return OrderBookInstrumentResponseMapper.toResponse(instrument, findPrice(instrument));
    }

    @Transactional
    public OrderBookInstrumentResponse updateTradingRules(String symbol, OrderBookInstrumentTradingRulesRequest request) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        BigDecimal priceLimitRate = request == null ? null : request.priceLimitRate();
        validatePriceLimitRate(priceLimitRate);
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Unknown order book symbol: " + normalizedSymbol));
        instrument.updatePriceLimitRate(priceLimitRate);
        return OrderBookInstrumentResponseMapper.toResponse(instrument, findPrice(instrument));
    }

    private void validateInstrumentRequest(String symbol, String name, String market, OrderBookInstrumentRequest request) {
        if (!symbol.matches("[A-Z0-9]{2,20}")) {
            throw StockException.badRequest("Symbol must be 2-20 uppercase letters or digits");
        }
        if (name.length() > 120) {
            throw StockException.badRequest("Name must be 120 characters or less");
        }
        if (market.length() > 20) {
            throw StockException.badRequest("Market must be 20 characters or less");
        }
        if (request == null || request.initialPrice() == null || request.initialPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Initial price must be positive");
        }
        if (request.issuedShares() == null || request.issuedShares() <= 0) {
            throw StockException.badRequest("Issued shares must be positive");
        }
        validatePriceLimitRate(request.priceLimitRate() == null ? BigDecimal.valueOf(30) : request.priceLimitRate());
    }

    private void validatePriceLimitRate(BigDecimal priceLimitRate) {
        if (priceLimitRate == null || priceLimitRate.compareTo(BigDecimal.ZERO) <= 0 || priceLimitRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw StockException.badRequest("Price limit rate must be greater than 0 and 100 or less");
        }
    }

    private void seedListingAutoAccount(
            String symbol,
            String name,
            BigDecimal initialPrice,
            long tradableShares,
            ListingAutoAccountRequest request,
            LocalDateTime now
    ) {
        String listingUserKey = LISTING_SUPPLY_USER_KEY_PREFIX + symbol.toLowerCase(Locale.ROOT);
        String displayName = MarketTextNormalizer.text(request == null ? null : request.displayName());
        if (displayName.isBlank()) {
            displayName = name + " 상장주관사";
        }
        if (displayName.length() > 80) {
            throw StockException.badRequest("Listing auto account display name must be 80 characters or less");
        }
        jdbcTemplate.update(
                """
                insert into stock_account(
                    user_key, status, cash_balance, created_at, updated_at
                )
                values (?, 'ACTIVE', 0.00, ?, ?)
                """,
                listingUserKey,
                now,
                now
        );
        Long accountId = jdbcClient.sql("select id from stock_account where user_key = ?")
                .param(listingUserKey)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> StockException.notFound("Listing supply account not found after opening"));
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity, average_price, updated_at
                )
                values (?, ?, ?, ?, ?, ?)
                """,
                accountId,
                symbol,
                tradableShares,
                0L,
                initialPrice,
                now
        );
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(symbol, listingUserKey, displayName, tradableShares, now);
        config.initializeIssueBasis(tradableShares, initialPrice);
        if (request != null) {
            config.update(
                    displayName,
                    request.enabled(),
                    request.positionSide(),
                    request.operationMode(),
                    request.strategyProfile(),
                    request.maxOrderQuantity(),
                    request.orderTtlSeconds(),
                    request.priceOffsetTicks(),
                    request.targetSpreadTicks(),
                    request.inventorySkewTicks(),
                    request.minimumProfitRate(),
                    request.aggressiveUnwindThreshold(),
                    request.aggressiveOrderRatio(),
                    request.targetBuyQuantity(),
                    request.targetSellQuantity(),
                    request.targetHoldingQuantity(),
                    request.inventoryBandQuantity(),
                    now
            );
            ListingAutoAccountConfigValidator.validate(config, tradableShares);
        }
        stockListingAutoAccountConfigRepository.save(config);
    }

    private StockPrice findPrice(StockOrderBookInstrument instrument) {
        return stockPriceRepository.findById(instrument.getSymbol()).orElse(null);
    }

}
