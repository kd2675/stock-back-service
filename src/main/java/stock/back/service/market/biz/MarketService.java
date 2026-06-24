package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.OrderType;
import stock.back.service.database.entity.PortfolioSnapshot;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfigId;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlement;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockInstrument;
import stock.back.service.database.entity.StockInstrumentReportEvent;
import stock.back.service.database.entity.StockInstrumentReportEventType;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.entity.StockPriceTick;
import stock.back.service.database.entity.StockVirtualMarketConfig;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockInstrumentReportEventRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.database.repository.StockPriceTickRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.database.repository.StockVirtualMarketConfigRepository;
import stock.back.service.market.cache.CachedStockPrice;
import stock.back.service.market.cache.StockPriceCacheService;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketConfigUpdateRequest;
import stock.back.service.market.vo.AutoMarketStatusResponse;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentResponse;
import stock.back.service.market.vo.AutoParticipantRequest;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;
import stock.back.service.market.vo.CorporateActionEntitlementResponse;
import stock.back.service.market.vo.CorporateActionRequest;
import stock.back.service.market.vo.CorporateActionResponse;
import stock.back.service.market.vo.InstrumentResponse;
import stock.back.service.market.vo.InstrumentReportRequest;
import stock.back.service.market.vo.InstrumentReportResponse;
import stock.back.service.market.vo.MarketStatusUpdateRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;
import stock.back.service.market.vo.OrderBookLevelResponse;
import stock.back.service.market.vo.OrderBookMarketStatusResponse;
import stock.back.service.market.vo.OrderBookResponse;
import stock.back.service.market.vo.PriceResponse;
import stock.back.service.market.vo.PriceTickResponse;
import stock.back.service.market.vo.RankingResponse;
import stock.back.service.market.vo.SymbolMarketConfigResponse;
import stock.back.service.market.vo.VirtualMarketStatusResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class MarketService {

    private static final String LISTING_SUPPLY_USER_KEY_PREFIX = "stock-listing-";
    private static final String LISTING_SUPPLY_CLIENT_ORDER_PREFIX = "listing-";

    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockPriceTickRepository stockPriceTickRepository;
    private final StockOrderRepository stockOrderRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private final StockAccountRepository stockAccountRepository;
    private final StockPriceCacheService stockPriceCacheService;
    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockAutoParticipantRepository stockAutoParticipantRepository;
    private final StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository;
    private final StockVirtualMarketConfigRepository stockVirtualMarketConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
    private final StockExecutionMarketViewRepository stockExecutionMarketViewRepository;
    private final StockCorporateActionRepository stockCorporateActionRepository;
    private final StockCorporateActionEntitlementRepository stockCorporateActionEntitlementRepository;
    private final StockInstrumentReportEventRepository stockInstrumentReportEventRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public List<InstrumentResponse> getInstruments() {
        return stockInstrumentRepository.findByEnabledTrueOrderBySymbolAsc().stream()
                .map(this::toInstrumentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> getPrices() {
        return stockPriceRepository.findVirtualMarketPrices().stream()
                .map(this::toPriceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderBookInstrumentResponse> getOrderBookInstruments() {
        return stockOrderBookInstrumentRepository.findByEnabledTrueOrderBySymbolAsc().stream()
                .map(this::toOrderBookInstrumentResponse)
                .toList();
    }

    @Transactional
    public OrderBookInstrumentResponse createOrderBookInstrument(OrderBookInstrumentRequest request) {
        String symbol = normalizeSymbol(request == null ? null : request.symbol());
        String name = normalizeText(request == null ? null : request.name());
        String market = normalizeText(request == null ? null : request.market());
        if (symbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (name.isBlank()) {
            throw StockException.badRequest("Name is required");
        }
        if (market.isBlank()) {
            market = "ORDERBOOK";
        }
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
        if (request == null || request.issuedShares() == null || request.issuedShares() <= 0) {
            throw StockException.badRequest("Issued shares must be positive");
        }
        BigDecimal tickSize = request.tickSize() == null ? BigDecimal.ONE : request.tickSize();
        BigDecimal priceLimitRate = request.priceLimitRate() == null ? BigDecimal.valueOf(30) : request.priceLimitRate();
        if (tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Tick size must be positive");
        }
        if (priceLimitRate.compareTo(BigDecimal.ZERO) <= 0 || priceLimitRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw StockException.badRequest("Price limit rate must be greater than 0 and 100 or less");
        }
        if (stockInstrumentRepository.existsById(symbol)) {
            throw StockException.conflict("Symbol already exists in virtual price market: " + symbol);
        }
        if (stockOrderBookInstrumentRepository.existsById(symbol)) {
            throw StockException.conflict("Order book symbol already exists: " + symbol);
        }

        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.save(
                StockOrderBookInstrument.listed(symbol, name, market, request.initialPrice(), request.issuedShares(), tickSize, priceLimitRate)
        );
        stockCorporateActionRepository.save(
                StockCorporateAction.initialIssue(symbol, request.issuedShares(), request.initialPrice())
        );
        stockOrderBookMarketConfigRepository.save(StockOrderBookMarketConfig.enabled(symbol));
        stockAutoMarketConfigRepository.save(StockAutoMarketConfig.defaults(symbol));
        stockPriceRepository.save(StockPrice.initial(symbol, request.initialPrice()));
        seedInitialListingSupply(symbol, request.initialPrice(), request.issuedShares());
        return toOrderBookInstrumentResponse(instrument);
    }

    private void seedInitialListingSupply(String symbol, BigDecimal initialPrice, long tradableShares) {
        LocalDateTime now = LocalDateTime.now();
        String listingUserKey = LISTING_SUPPLY_USER_KEY_PREFIX + symbol.toLowerCase(Locale.ROOT);
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
        Long accountId = jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                listingUserKey
        );
        if (accountId == null) {
            throw StockException.notFound("Listing supply account not found after opening");
        }
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
                tradableShares,
                initialPrice,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, symbol, market_type, side, order_type, status,
                    limit_price, quantity, filled_quantity, average_fill_price,
                    reserved_cash, created_at, updated_at
                )
                values (?, ?, ?, 'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING', ?, ?, 0, null, 0, ?, ?)
                """,
                LISTING_SUPPLY_CLIENT_ORDER_PREFIX + symbol,
                accountId,
                symbol,
                initialPrice,
                tradableShares,
                now,
                now
        );
    }

    @Transactional
    public OrderBookInstrumentResponse applyCorporateAction(String symbol, CorporateActionRequest request) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (request == null || request.actionType() == null) {
            throw StockException.badRequest("Corporate action type is required");
        }
        if (request.actionType() == StockCorporateActionType.INITIAL_ISSUE) {
            throw StockException.badRequest("Initial issue is only allowed when creating an instrument");
        }
        validateCorporateActionFieldScope(request);
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Unknown order book symbol: " + normalizedSymbol));
        assertNoOpenOrderBookOrders(normalizedSymbol);

        return switch (request.actionType()) {
            case PAID_IN_CAPITAL_INCREASE, ADDITIONAL_ISSUE -> applyShareIssue(instrument, request);
            case BONUS_ISSUE, STOCK_DIVIDEND -> applyFreeShareDistribution(instrument, request);
            case STOCK_SPLIT -> applyStockSplit(instrument, request);
            case CASH_DIVIDEND -> applyCashDividend(instrument, request);
            case INITIAL_ISSUE -> throw StockException.badRequest("Initial issue is only allowed when creating an instrument");
        };
    }

    @Transactional(readOnly = true)
    public List<CorporateActionResponse> getCorporateActions(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        return stockCorporateActionRepository.findBySymbolOrderByCreatedAtDesc(normalizedSymbol).stream()
                .map(this::toCorporateActionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstrumentReportResponse> getInstrumentReports(String symbol) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        return stockInstrumentReportEventRepository.findTop50BySymbolOrderByCreatedAtDescIdDesc(normalizedSymbol).stream()
                .map(this::toInstrumentReportResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public InstrumentReportResponse getLatestInstrumentReport(String symbol) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        return stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc(normalizedSymbol)
                .filter(event -> event.getEventType() != StockInstrumentReportEventType.DELETE)
                .map(this::toInstrumentReportResponse)
                .orElse(null);
    }

    @Transactional
    public InstrumentReportResponse publishInstrumentReport(String symbol, InstrumentReportRequest request, String createdBy) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        validateInstrumentReportRequest(request);
        StockInstrumentReportEvent event = StockInstrumentReportEvent.publish(
                normalizedSymbol,
                normalizeText(request.title()),
                normalizeText(request.summary()),
                request.score(),
                normalizeText(request.riseReason()),
                normalizeText(request.fallReason()),
                normalizeText(createdBy)
        );
        return toInstrumentReportResponse(stockInstrumentReportEventRepository.save(event));
    }

    @Transactional
    public InstrumentReportResponse updateInstrumentReport(String symbol, InstrumentReportRequest request, String createdBy) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        validateInstrumentReportRequest(request);
        StockInstrumentReportEvent latest = stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc(normalizedSymbol)
                .filter(event -> event.getEventType() != StockInstrumentReportEventType.DELETE)
                .orElseThrow(() -> StockException.notFound("Instrument report not found: " + normalizedSymbol));
        StockInstrumentReportEvent event = StockInstrumentReportEvent.update(
                latest.getSymbol(),
                normalizeText(request.title()),
                normalizeText(request.summary()),
                request.score(),
                normalizeText(request.riseReason()),
                normalizeText(request.fallReason()),
                normalizeText(createdBy)
        );
        return toInstrumentReportResponse(stockInstrumentReportEventRepository.save(event));
    }

    @Transactional
    public InstrumentReportResponse deleteInstrumentReport(String symbol, String createdBy) {
        String normalizedSymbol = requireOrderBookSymbol(symbol);
        stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc(normalizedSymbol)
                .filter(event -> event.getEventType() != StockInstrumentReportEventType.DELETE)
                .orElseThrow(() -> StockException.notFound("Instrument report not found: " + normalizedSymbol));
        StockInstrumentReportEvent event = StockInstrumentReportEvent.delete(
                normalizedSymbol,
                "Deleted by admin",
                normalizeText(createdBy)
        );
        return toInstrumentReportResponse(stockInstrumentReportEventRepository.save(event));
    }

    @Transactional(readOnly = true)
    public List<CorporateActionEntitlementResponse> getMyCorporateActionEntitlements(String userKey) {
        Long accountId = stockAccountRepository.findByUserKeyAndStatus(userKey, StockAccountStatus.ACTIVE)
                .map(stock.back.service.database.entity.StockAccount::getId)
                .orElse(null);
        if (accountId == null) {
            return List.of();
        }
        List<StockCorporateActionEntitlement> entitlements =
                stockCorporateActionEntitlementRepository.findTop50ByAccountIdOrderByCreatedAtDesc(accountId);
        Map<Long, StockCorporateAction> actionsById = stockCorporateActionRepository.findAllById(
                        entitlements.stream()
                                .map(StockCorporateActionEntitlement::getActionId)
                                .toList()
                ).stream()
                .collect(Collectors.toMap(StockCorporateAction::getId, Function.identity()));
        return entitlements.stream()
                .map(entitlement -> toCorporateActionEntitlementResponse(entitlement, actionsById.get(entitlement.getActionId())))
                .toList();
    }

    @Transactional
    public SymbolMarketConfigResponse updateMarketStatus(MarketType marketType, String symbol, MarketStatusUpdateRequest request) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (marketType == null) {
            throw StockException.badRequest("Market type is required");
        }
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (request == null || (request.enabled() == null && request.marketStatus() == null)) {
            throw StockException.badRequest("Market status update requires enabled or marketStatus");
        }
        if (marketType == MarketType.VIRTUAL_PRICE) {
            StockVirtualMarketConfig config = stockVirtualMarketConfigRepository.findById(normalizedSymbol)
                    .orElseThrow(() -> StockException.notFound("Unknown virtual market symbol: " + normalizedSymbol));
            config.updateStatus(request.enabled(), request.marketStatus());
            return toVirtualMarketConfigResponse(config);
        }
        StockOrderBookMarketConfig config = stockOrderBookMarketConfigRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Unknown order book market symbol: " + normalizedSymbol));
        config.updateStatus(request.enabled(), request.marketStatus());
        return toOrderBookMarketConfigResponse(config);
    }

    @Transactional(readOnly = true)
    public List<RankingResponse> getRankings() {
        LocalDate rankingDate = portfolioSnapshotRepository.findTopByOrderBySnapshotDateDesc()
                .map(PortfolioSnapshot::getSnapshotDate)
                .orElse(null);
        if (rankingDate == null) {
            return List.of();
        }
        AtomicInteger rank = new AtomicInteger(1);
        return portfolioSnapshotRepository.findTop20BySnapshotDateOrderByReturnRateDesc(rankingDate).stream()
                .map(snapshot -> toRankingResponse(rank.getAndIncrement(), snapshot))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PriceTickResponse> getPriceTicks(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        return stockPriceTickRepository.findTop100BySymbolOrderByPriceTimeDesc(normalizedSymbol).stream()
                .map(this::toPriceTickResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderBookResponse getOrderBook(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsBySymbolAndEnabledTrue(normalizedSymbol)) {
            throw StockException.notFound("Unknown stock symbol: " + normalizedSymbol);
        }
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        var page = PageRequest.of(0, 10);
        List<OrderBookLevelResponse> bids = stockOrderRepository
                .findBidLevels(normalizedSymbol, MarketType.ORDER_BOOK, OrderSide.BUY, OrderType.LIMIT, openStatuses, page)
                .stream()
                .map(this::toOrderBookLevelResponse)
                .toList();
        List<OrderBookLevelResponse> asks = stockOrderRepository
                .findAskLevels(normalizedSymbol, MarketType.ORDER_BOOK, OrderSide.SELL, OrderType.LIMIT, openStatuses, page)
                .stream()
                .map(this::toOrderBookLevelResponse)
                .toList();
        return new OrderBookResponse(normalizedSymbol, bids, asks);
    }

    @Transactional(readOnly = true)
    public VirtualMarketStatusResponse getVirtualMarketStatus() {
        List<SymbolMarketConfigResponse> configs = stockVirtualMarketConfigRepository.findAll().stream()
                .sorted((left, right) -> left.getSymbol().compareTo(right.getSymbol()))
                .map(this::toVirtualMarketConfigResponse)
                .toList();
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        long openOrderCount = stockOrderRepository.countByMarketTypeAndStatusIn(MarketType.VIRTUAL_PRICE, openStatuses);
        long todayExecutionCount = stockExecutionMarketViewRepository.countExecutionsFromBySource(
                LocalDate.now().atStartOfDay(),
                ExecutionSource.VIRTUAL_MARKET_PRICE
        );
        return new VirtualMarketStatusResponse(
                configs.stream().anyMatch(this::isConfigOpen),
                openOrderCount,
                todayExecutionCount,
                configs
        );
    }

    @Transactional(readOnly = true)
    public OrderBookMarketStatusResponse getOrderBookMarketStatus() {
        List<SymbolMarketConfigResponse> configs = stockOrderBookMarketConfigRepository.findAll().stream()
                .sorted((left, right) -> left.getSymbol().compareTo(right.getSymbol()))
                .map(this::toOrderBookMarketConfigResponse)
                .toList();
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        long openOrderCount = stockOrderRepository.countByMarketTypeAndStatusIn(MarketType.ORDER_BOOK, openStatuses);
        long todayExecutionCount = stockExecutionMarketViewRepository.countExecutionsFromBySource(
                LocalDate.now().atStartOfDay(),
                ExecutionSource.INTERNAL_ORDER_BOOK
        );
        return new OrderBookMarketStatusResponse(
                configs.stream().anyMatch(this::isConfigOpen),
                openOrderCount,
                todayExecutionCount,
                configs
        );
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus() {
        List<StockAutoMarketConfig> configEntities = stockAutoMarketConfigRepository.findAll().stream()
                .sorted((left, right) -> left.getSymbol().compareTo(right.getSymbol()))
                .toList();
        List<AutoMarketConfigResponse> configs = configEntities.stream()
                .map(this::toAutoMarketConfigResponse)
                .toList();
        List<StockAutoParticipant> participantEntities = stockAutoParticipantRepository.findByWithdrawnAtIsNullOrderByUserKeyAsc();
        List<AutoParticipantResponse> participants = participantEntities.stream()
                .map(this::toAutoParticipantResponse)
                .toList();
        Map<String, StockAutoParticipantSymbolConfig> savedParticipantSymbolConfigs = stockAutoParticipantSymbolConfigRepository.findAllByOrderByUserKeyAscSymbolAsc()
                .stream()
                .collect(Collectors.toMap(
                        config -> autoParticipantSymbolConfigKey(config.getUserKey(), config.getSymbol()),
                        Function.identity(),
                        (left, right) -> left
                ));
        List<AutoParticipantSymbolConfigResponse> participantSymbolConfigs = participantEntities.stream()
                .flatMap(participant -> configEntities.stream()
                        .map(config -> toEffectiveAutoParticipantSymbolConfigResponse(
                                participant,
                                config,
                                savedParticipantSymbolConfigs.get(autoParticipantSymbolConfigKey(participant.getUserKey(), config.getSymbol()))
                        )))
                .toList();
        long enabledParticipantCount = stockAutoParticipantRepository.countByEnabledTrueAndWithdrawnAtIsNull();
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        long openAutoOrderCount = stockOrderRepository.countOpenAutoOrders(openStatuses, MarketType.ORDER_BOOK);
        long todayAutoExecutionCount = stockExecutionMarketViewRepository.countAutoExecutionsFrom(LocalDate.now().atStartOfDay());
        boolean enabled = enabledParticipantCount > 0 && configs.stream().anyMatch(AutoMarketConfigResponse::enabled);
        return new AutoMarketStatusResponse(
                enabled,
                enabledParticipantCount,
                openAutoOrderCount,
                todayAutoExecutionCount,
                configs,
                participants,
                participantSymbolConfigs
        );
    }

    @Transactional
    public AutoMarketConfigResponse updateAutoMarketConfig(String symbol, AutoMarketConfigUpdateRequest request) {
        String normalizedSymbol = normalizeSymbol(symbol);
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
        StockAutoParticipant participant = stockAutoParticipantRepository.findById(normalizedUserKey)
                .map(existing -> {
                    existing.update(displayName, request == null ? null : request.enabled());
                    return existing;
                })
                .orElseGet(() -> {
                    return StockAutoParticipant.create(
                        normalizedUserKey,
                        displayName,
                        request == null || request.enabled() == null || request.enabled()
                    );
                });
        return toAutoParticipantResponse(stockAutoParticipantRepository.save(participant));
    }

    @Transactional
    public AutoParticipantCashAdjustmentResponse adjustAutoParticipantCash(
            String userKey,
            AutoParticipantCashAdjustmentRequest request,
            String adminUserKey
    ) {
        String normalizedUserKey = normalizeText(userKey);
        if (normalizedUserKey.isBlank()) {
            throw StockException.badRequest("Auto participant user key is required");
        }
        StockAutoParticipant participant = stockAutoParticipantRepository.findById(normalizedUserKey)
                .orElseThrow(() -> StockException.notFound("Unknown auto participant: " + normalizedUserKey));
        if (participant.getWithdrawnAt() != null) {
            throw StockException.notFound("Unknown auto participant: " + normalizedUserKey);
        }
        BigDecimal amount = request == null ? null : request.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Adjustment amount must be positive");
        }
        String adjustmentType = normalizeText(request.adjustmentType()).toUpperCase(Locale.ROOT);
        if (!"DEPOSIT".equals(adjustmentType) && !"WITHDRAW".equals(adjustmentType)) {
            throw StockException.badRequest("Adjustment type must be DEPOSIT or WITHDRAW");
        }
        StockAccount account = stockAccountRepository.findByUserKeyAndStatusForUpdate(normalizedUserKey, StockAccountStatus.ACTIVE)
                .orElseThrow(() -> StockException.notFound("Auto participant account is not opened yet: " + normalizedUserKey));
        if ("DEPOSIT".equals(adjustmentType)) {
            account.depositCash(amount);
            stockAccountCashFlowRepository.save(StockAccountCashFlow.adminDeposit(account.getId(), amount, normalizeText(adminUserKey)));
        } else if (!account.withdrawCash(amount)) {
            throw StockException.badRequest("Insufficient auto participant cash balance");
        } else {
            stockAccountCashFlowRepository.save(StockAccountCashFlow.adminWithdraw(account.getId(), amount, normalizeText(adminUserKey)));
        }
        return new AutoParticipantCashAdjustmentResponse(
                normalizedUserKey,
                adjustmentType,
                amount,
                account.getCashBalance(),
                account.getUpdatedAt()
        );
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

    @Transactional
    public AutoParticipantSymbolConfigResponse updateAutoParticipantSymbolConfig(
            String userKey,
            String symbol,
            AutoParticipantSymbolConfigRequest request
    ) {
        String normalizedUserKey = normalizeText(userKey);
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedUserKey.isBlank()) {
            throw StockException.badRequest("Auto participant user key is required");
        }
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        StockAutoParticipant participant = stockAutoParticipantRepository.findById(normalizedUserKey)
                .orElseThrow(() -> StockException.notFound("Unknown auto participant: " + normalizedUserKey));
        if (participant.getWithdrawnAt() != null) {
            throw StockException.notFound("Unknown auto participant: " + normalizedUserKey);
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        StockAutoMarketConfig marketConfig = stockAutoMarketConfigRepository.findById(normalizedSymbol)
                .orElseGet(() -> StockAutoMarketConfig.defaults(normalizedSymbol));
        Integer intensity = request == null ? null : request.intensity();
        if (intensity != null && (intensity < 1 || intensity > 10)) {
            throw StockException.badRequest("Intensity must be between 1 and 10");
        }
        StockAutoParticipantSymbolConfigId id = new StockAutoParticipantSymbolConfigId(normalizedUserKey, normalizedSymbol);
        StockAutoParticipantSymbolConfig config = stockAutoParticipantSymbolConfigRepository.findById(id)
                .orElseGet(() -> StockAutoParticipantSymbolConfig.defaults(
                        normalizedUserKey,
                        normalizedSymbol,
                        marketConfig.getIntensity() == null ? 5 : marketConfig.getIntensity()
                ));
        config.update(request == null ? null : request.enabled(), intensity);
        return toAutoParticipantSymbolConfigResponse(stockAutoParticipantSymbolConfigRepository.save(config));
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private String requireOrderBookSymbol(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (!stockOrderBookInstrumentRepository.existsById(normalizedSymbol)) {
            throw StockException.notFound("Unknown order book symbol: " + normalizedSymbol);
        }
        return normalizedSymbol;
    }

    private void validateInstrumentReportRequest(InstrumentReportRequest request) {
        if (request == null) {
            throw StockException.badRequest("Instrument report is required");
        }
        if (normalizeText(request.title()).isBlank()) {
            throw StockException.badRequest("Report title is required");
        }
        if (normalizeText(request.summary()).isBlank()) {
            throw StockException.badRequest("Report summary is required");
        }
        if (request.score() == null || request.score() < 1 || request.score() > 10) {
            throw StockException.badRequest("Report score must be between 1 and 10");
        }
        if (normalizeText(request.riseReason()).isBlank()) {
            throw StockException.badRequest("Rise reason is required");
        }
        if (normalizeText(request.fallReason()).isBlank()) {
            throw StockException.badRequest("Fall reason is required");
        }
    }

    private void cancelOpenAutoParticipantOrders(String userKey) {
        Long accountId = stockAccountRepository.findByUserKeyAndStatus(userKey, StockAccountStatus.ACTIVE)
                .map(stock.back.service.database.entity.StockAccount::getId)
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

    private void validateCorporateActionFieldScope(CorporateActionRequest request) {
        switch (request.actionType()) {
            case PAID_IN_CAPITAL_INCREASE -> {
                rejectPresent(request.splitFrom(), "Paid-in capital increase does not use splitFrom");
                rejectPresent(request.splitTo(), "Paid-in capital increase does not use splitTo");
                rejectPresent(request.dividendAmount(), "Paid-in capital increase does not use dividendAmount");
            }
            case ADDITIONAL_ISSUE -> {
                rejectPresent(request.splitFrom(), "Additional issue does not use splitFrom");
                rejectPresent(request.splitTo(), "Additional issue does not use splitTo");
                rejectPresent(request.exRightsDate(), "Additional issue does not use exRightsDate");
                rejectPresent(request.paymentDate(), "Additional issue does not use paymentDate");
                rejectPresent(request.dividendAmount(), "Additional issue does not use dividendAmount");
            }
            case STOCK_SPLIT -> {
                rejectPresent(request.shareQuantity(), "Stock split does not use shareQuantity");
                rejectPresent(request.issuePrice(), "Stock split does not use issuePrice");
                rejectPresent(request.exRightsDate(), "Stock split does not use exRightsDate");
                rejectPresent(request.paymentDate(), "Stock split does not use paymentDate");
                rejectPresent(request.dividendAmount(), "Stock split does not use dividendAmount");
            }
            case CASH_DIVIDEND -> {
                rejectPresent(request.shareQuantity(), "Cash dividend does not use shareQuantity");
                rejectPresent(request.issuePrice(), "Cash dividend does not use issuePrice");
                rejectPresent(request.splitFrom(), "Cash dividend does not use splitFrom");
                rejectPresent(request.splitTo(), "Cash dividend does not use splitTo");
                rejectPresent(request.listingDate(), "Cash dividend does not use listingDate");
            }
            case BONUS_ISSUE, STOCK_DIVIDEND -> {
                rejectPresent(request.issuePrice(), "Free share distribution does not use issuePrice");
                rejectPresent(request.splitFrom(), "Free share distribution does not use splitFrom");
                rejectPresent(request.splitTo(), "Free share distribution does not use splitTo");
                rejectPresent(request.paymentDate(), "Free share distribution does not use paymentDate");
                rejectPresent(request.dividendAmount(), "Free share distribution does not use dividendAmount");
            }
            case INITIAL_ISSUE -> throw StockException.badRequest("Initial issue is only allowed when creating an instrument");
        }
    }

    private void rejectPresent(Object value, String message) {
        if (value != null) {
            throw StockException.badRequest(message);
        }
    }

    private OrderBookInstrumentResponse applyShareIssue(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        long shares = request.shareQuantity() == null ? 0L : request.shareQuantity();
        if (shares <= 0) {
            throw StockException.badRequest("Share quantity must be positive");
        }
        BigDecimal issuePrice = request.issuePrice();
        if (issuePrice == null || issuePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Share issue requires a positive issue price");
        }

        if (request.actionType() == StockCorporateActionType.PAID_IN_CAPITAL_INCREASE) {
            return announcePaidInCapitalIncrease(instrument, request, shares, issuePrice);
        }

        return announceAdditionalIssue(instrument, request, shares, issuePrice);
    }

    private OrderBookInstrumentResponse announcePaidInCapitalIncrease(
            StockOrderBookInstrument instrument,
            CorporateActionRequest request,
            long shares,
            BigDecimal issuePrice
    ) {
        LocalDate exRightsDate = request.exRightsDate();
        LocalDate paymentDate = request.paymentDate();
        LocalDate listingDate = request.listingDate();
        if (exRightsDate == null || paymentDate == null || listingDate == null) {
            throw StockException.badRequest("Paid-in capital increase requires ex-rights, payment, and listing dates");
        }
        if (paymentDate.isBefore(exRightsDate) || listingDate.isBefore(paymentDate)) {
            throw StockException.badRequest("Paid-in capital increase dates must be ordered by ex-rights, payment, listing");
        }

        StockPrice price = stockPriceRepository.findById(instrument.getSymbol())
                .orElseThrow(() -> StockException.notFound("Price not found: " + instrument.getSymbol()));
        BigDecimal basePrice = price.getCurrentPrice();
        BigDecimal theoreticalExRightsPrice = calculateTheoreticalExRightsPrice(
                instrument.getIssuedShares(),
                basePrice,
                shares,
                issuePrice
        );
        stockCorporateActionRepository.save(StockCorporateAction.paidInCapitalIncrease(
                instrument.getSymbol(),
                shares,
                issuePrice,
                basePrice,
                theoreticalExRightsPrice,
                exRightsDate,
                paymentDate,
                listingDate,
                normalizeNullableDescription(request.description())
        ));
        return toOrderBookInstrumentResponse(instrument);
    }

    private OrderBookInstrumentResponse announceAdditionalIssue(
            StockOrderBookInstrument instrument,
            CorporateActionRequest request,
            long shares,
            BigDecimal issuePrice
    ) {
        LocalDate listingDate = request.listingDate();
        if (listingDate == null) {
            throw StockException.badRequest("Additional issue requires a listing date");
        }
        stockCorporateActionRepository.save(StockCorporateAction.additionalIssue(
                instrument.getSymbol(),
                shares,
                issuePrice,
                listingDate,
                normalizeNullableDescription(request.description())
        ));
        return toOrderBookInstrumentResponse(instrument);
    }

    private BigDecimal calculateTheoreticalExRightsPrice(
            long existingShares,
            BigDecimal basePrice,
            long newShares,
            BigDecimal issuePrice
    ) {
        BigDecimal existingValue = basePrice.multiply(BigDecimal.valueOf(existingShares));
        BigDecimal issueValue = issuePrice.multiply(BigDecimal.valueOf(newShares));
        return existingValue.add(issueValue)
                .divide(BigDecimal.valueOf(existingShares + newShares), 2, RoundingMode.HALF_UP);
    }

    private OrderBookInstrumentResponse applyFreeShareDistribution(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        long shares = request.shareQuantity() == null ? 0L : request.shareQuantity();
        if (shares <= 0) {
            throw StockException.badRequest("Share quantity must be positive");
        }
        LocalDate exRightsDate = request.exRightsDate();
        LocalDate listingDate = request.listingDate();
        if (exRightsDate == null || listingDate == null) {
            throw StockException.badRequest("Free share distribution requires ex-rights and listing dates");
        }
        if (listingDate.isBefore(exRightsDate)) {
            throw StockException.badRequest("Free share distribution listing date must be on or after ex-rights date");
        }

        StockPrice price = stockPriceRepository.findById(instrument.getSymbol())
                .orElseThrow(() -> StockException.notFound("Price not found: " + instrument.getSymbol()));
        BigDecimal basePrice = price.getCurrentPrice();
        BigDecimal theoreticalExRightsPrice = calculateTheoreticalFreeSharePrice(
                instrument.getIssuedShares(),
                basePrice,
                shares
        );
        StockCorporateAction action = request.actionType() == StockCorporateActionType.BONUS_ISSUE
                ? StockCorporateAction.bonusIssue(
                        instrument.getSymbol(),
                        shares,
                        basePrice,
                        theoreticalExRightsPrice,
                        exRightsDate,
                        listingDate,
                        normalizeNullableDescription(request.description())
                )
                : StockCorporateAction.stockDividend(
                        instrument.getSymbol(),
                        shares,
                        basePrice,
                        theoreticalExRightsPrice,
                        exRightsDate,
                        listingDate,
                        normalizeNullableDescription(request.description())
                );
        stockCorporateActionRepository.save(action);
        return toOrderBookInstrumentResponse(instrument);
    }

    private BigDecimal calculateTheoreticalFreeSharePrice(
            long existingShares,
            BigDecimal basePrice,
            long newShares
    ) {
        BigDecimal existingValue = basePrice.multiply(BigDecimal.valueOf(existingShares));
        return existingValue.divide(BigDecimal.valueOf(existingShares + newShares), 2, RoundingMode.HALF_UP);
    }

    private OrderBookInstrumentResponse applyStockSplit(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        Integer splitFrom = request.splitFrom();
        Integer splitTo = request.splitTo();
        if (splitFrom == null || splitTo == null || splitFrom <= 0 || splitTo <= 0 || splitTo <= splitFrom) {
            throw StockException.badRequest("Stock split ratio must be positive and greater than 1:1");
        }
        if (splitTo % splitFrom != 0) {
            throw StockException.badRequest("Only integer share split ratios are supported");
        }
        LocalDate listingDate = request.listingDate();
        if (listingDate == null) {
            throw StockException.badRequest("Stock split requires an effective date");
        }
        stockCorporateActionRepository.save(StockCorporateAction.stockSplit(
                instrument.getSymbol(),
                splitFrom,
                splitTo,
                listingDate,
                normalizeNullableDescription(request.description())
        ));
        return toOrderBookInstrumentResponse(instrument);
    }

    private OrderBookInstrumentResponse applyCashDividend(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        BigDecimal dividendAmount = request.dividendAmount();
        if (dividendAmount == null || dividendAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Cash dividend amount must be positive");
        }
        LocalDate exRightsDate = request.exRightsDate();
        LocalDate paymentDate = request.paymentDate();
        if (exRightsDate == null || paymentDate == null) {
            throw StockException.badRequest("Cash dividend requires ex-dividend and payment dates");
        }
        if (paymentDate.isBefore(exRightsDate)) {
            throw StockException.badRequest("Cash dividend payment date must be on or after ex-dividend date");
        }

        StockPrice price = stockPriceRepository.findById(instrument.getSymbol())
                .orElseThrow(() -> StockException.notFound("Price not found: " + instrument.getSymbol()));
        BigDecimal basePrice = price.getCurrentPrice();
        BigDecimal theoreticalExRightsPrice = basePrice.subtract(dividendAmount).setScale(2, RoundingMode.HALF_UP);
        if (theoreticalExRightsPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Cash dividend amount must be less than the current price");
        }

        stockCorporateActionRepository.save(StockCorporateAction.cashDividend(
                instrument.getSymbol(),
                dividendAmount,
                basePrice,
                theoreticalExRightsPrice,
                exRightsDate,
                paymentDate,
                normalizeNullableDescription(request.description())
        ));
        return toOrderBookInstrumentResponse(instrument);
    }

    private void assertNoOpenOrderBookOrders(String symbol) {
        Long openOrderCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_order
                 where symbol = ?
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """,
                Long.class,
                symbol
        );
        if (openOrderCount != null && openOrderCount > 0) {
            throw StockException.conflict("Corporate action requires no open order book orders: " + symbol);
        }
    }

    private String normalizeNullableDescription(String value) {
        String description = normalizeText(value);
        if (description.isBlank()) {
            return null;
        }
        if (description.length() > 255) {
            throw StockException.badRequest("Description must be 255 characters or less");
        }
        return description;
    }

    private InstrumentResponse toInstrumentResponse(StockInstrument instrument) {
        return new InstrumentResponse(instrument.getSymbol(), instrument.getName(), instrument.getMarket());
    }

    private OrderBookInstrumentResponse toOrderBookInstrumentResponse(StockOrderBookInstrument instrument) {
        StockPrice price = stockPriceRepository.findById(instrument.getSymbol()).orElse(null);
        BigDecimal currentPrice = price == null ? instrument.getInitialPrice() : price.getCurrentPrice();
        BigDecimal priceLimitBase = price == null ? instrument.getInitialPrice() : price.getPreviousClose();
        LocalDateTime priceTime = price == null ? instrument.getUpdatedAt() : price.getPriceTime();
        String priceProvider = price == null ? "order-book-initial" : price.getProvider();
        return new OrderBookInstrumentResponse(
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getMarket(),
                instrument.getInitialPrice(),
                instrument.getIssuedShares(),
                instrument.getTradableShares(),
                instrument.getTickSize(),
                instrument.getPriceLimitRate(),
                priceLimitBase,
                currentPrice,
                priceTime,
                priceProvider,
                Boolean.TRUE.equals(instrument.getEnabled()),
                instrument.getCreatedAt(),
                instrument.getUpdatedAt()
        );
    }

    private PriceResponse toPriceResponse(StockPrice price) {
        var cachedPrice = stockPriceCacheService.getCachedPrice(price.getSymbol());
        BigDecimal currentPrice = cachedPrice
                .map(CachedStockPrice::currentPrice)
                .orElse(price.getCurrentPrice());
        String provider = cachedPrice
                .map(CachedStockPrice::provider)
                .orElse(price.getProvider());

        BigDecimal changeRate = BigDecimal.ZERO;
        if (price.getPreviousClose().compareTo(BigDecimal.ZERO) > 0) {
            changeRate = currentPrice
                    .subtract(price.getPreviousClose())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(price.getPreviousClose(), 4, RoundingMode.HALF_UP);
        }
        return new PriceResponse(
                price.getSymbol(),
                currentPrice,
                price.getPreviousClose(),
                changeRate,
                price.getPriceTime(),
                provider
        );
    }

    private RankingResponse toRankingResponse(int rank, PortfolioSnapshot snapshot) {
        String userKey = stockAccountRepository.findById(snapshot.getAccountId())
                .map(stock.back.service.database.entity.StockAccount::getUserKey)
                .orElse(null);
        return new RankingResponse(
                rank,
                snapshot.getAccountId(),
                userKey,
                toRankingDisplayName(userKey),
                snapshot.getTotalAsset(),
                snapshot.getReturnRate(),
                snapshot.getSnapshotDate()
        );
    }

    private CorporateActionResponse toCorporateActionResponse(StockCorporateAction action) {
        return new CorporateActionResponse(
                action.getId(),
                action.getSymbol(),
                action.getActionType(),
                action.getShareQuantity(),
                action.getIssuePrice(),
                action.getDividendAmount(),
                action.getStatus(),
                action.getBasePrice(),
                action.getTheoreticalExRightsPrice(),
                action.getExRightsDate(),
                action.getPaymentDate(),
                action.getListingDate(),
                action.getAppliedAt(),
                action.getPaidAt(),
                action.getListedAt(),
                action.getSplitFrom(),
                action.getSplitTo(),
                action.getDescription(),
                action.getCreatedAt()
        );
    }

    private InstrumentReportResponse toInstrumentReportResponse(StockInstrumentReportEvent event) {
        return new InstrumentReportResponse(
                event.getId(),
                event.getSymbol(),
                event.getEventType(),
                event.getTitle(),
                event.getSummary(),
                event.getScore(),
                event.getRiseReason(),
                event.getFallReason(),
                event.getDeleteReason(),
                event.getCreatedBy(),
                event.getCreatedAt()
        );
    }

    private CorporateActionEntitlementResponse toCorporateActionEntitlementResponse(
            StockCorporateActionEntitlement entitlement,
            StockCorporateAction action
    ) {
        return new CorporateActionEntitlementResponse(
                entitlement.getId(),
                entitlement.getAccountId(),
                entitlement.getActionId(),
                entitlement.getSymbol(),
                action == null ? null : action.getActionType(),
                entitlement.getQuantity() == null ? 0L : entitlement.getQuantity(),
                entitlement.getShareQuantity(),
                entitlement.getCashAmount(),
                entitlement.getStatus(),
                entitlement.getCreatedAt(),
                entitlement.getPaidAt()
        );
    }

    private String toRankingDisplayName(String userKey) {
        if (userKey == null || userKey.isBlank()) {
            return "익명 투자자";
        }
        String normalized = userKey.trim();
        int visibleLength = Math.min(6, normalized.length());
        return "투자자 " + normalized.substring(normalized.length() - visibleLength);
    }

    private PriceTickResponse toPriceTickResponse(StockPriceTick tick) {
        return new PriceTickResponse(tick.getSymbol(), tick.getPrice(), tick.getProvider(), tick.getPriceTime());
    }

    private AutoMarketConfigResponse toAutoMarketConfigResponse(StockAutoMarketConfig config) {
        return new AutoMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getIntensity() == null ? 0 : config.getIntensity(),
                config.getMaxOrderQuantity() == null ? 0 : config.getMaxOrderQuantity(),
                config.getOrderTtlSeconds() == null ? 0 : config.getOrderTtlSeconds()
        );
    }

    private AutoParticipantResponse toAutoParticipantResponse(StockAutoParticipant participant) {
        return new AutoParticipantResponse(
                participant.getUserKey(),
                participant.getDisplayName(),
                Boolean.TRUE.equals(participant.getEnabled()),
                findAutoParticipantCashBalance(participant.getUserKey()),
                participant.getCreatedAt(),
                participant.getUpdatedAt(),
                participant.getWithdrawnAt()
        );
    }

    private BigDecimal findAutoParticipantCashBalance(String userKey) {
        return stockAccountRepository.findByUserKeyAndStatus(userKey, StockAccountStatus.ACTIVE)
                .map(stock.back.service.database.entity.StockAccount::getCashBalance)
                .orElse(null);
    }

    private AutoParticipantSymbolConfigResponse toAutoParticipantSymbolConfigResponse(StockAutoParticipantSymbolConfig config) {
        return new AutoParticipantSymbolConfigResponse(
                config.getUserKey(),
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getIntensity() == null ? 0 : config.getIntensity(),
                config.getUpdatedAt()
        );
    }

    private AutoParticipantSymbolConfigResponse toEffectiveAutoParticipantSymbolConfigResponse(
            StockAutoParticipant participant,
            StockAutoMarketConfig marketConfig,
            StockAutoParticipantSymbolConfig savedConfig
    ) {
        if (savedConfig != null) {
            return toAutoParticipantSymbolConfigResponse(savedConfig);
        }
        return new AutoParticipantSymbolConfigResponse(
                participant.getUserKey(),
                marketConfig.getSymbol(),
                true,
                marketConfig.getIntensity() == null ? 5 : marketConfig.getIntensity(),
                participant.getUpdatedAt() == null ? marketConfig.getUpdatedAt() : participant.getUpdatedAt()
        );
    }

    private String autoParticipantSymbolConfigKey(String userKey, String symbol) {
        return userKey + "\n" + symbol;
    }

    private SymbolMarketConfigResponse toVirtualMarketConfigResponse(StockVirtualMarketConfig config) {
        return new SymbolMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                normalizeMarketSessionStatus(config.getMarketStatus())
        );
    }

    private SymbolMarketConfigResponse toOrderBookMarketConfigResponse(StockOrderBookMarketConfig config) {
        return new SymbolMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                normalizeMarketSessionStatus(config.getMarketStatus())
        );
    }

    private boolean isConfigOpen(SymbolMarketConfigResponse config) {
        return config.enabled() && config.marketStatus() == MarketSessionStatus.OPEN;
    }

    private MarketSessionStatus normalizeMarketSessionStatus(MarketSessionStatus marketStatus) {
        return marketStatus == null ? MarketSessionStatus.OPEN : marketStatus;
    }

    private OrderBookLevelResponse toOrderBookLevelResponse(StockOrderRepository.OrderBookLevelView level) {
        return new OrderBookLevelResponse(
                level.getPrice(),
                level.getQuantity() == null ? 0L : level.getQuantity(),
                level.getOrderCount() == null ? 0L : level.getOrderCount()
        );
    }
}
