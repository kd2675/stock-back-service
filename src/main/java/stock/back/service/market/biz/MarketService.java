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
import stock.back.service.database.entity.RecurringCashIntervalUnit;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.ExecutionSource;
import stock.back.service.database.entity.ListingAutoPosition;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfigId;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.database.entity.StockAccountCashFlow;
import stock.back.service.database.entity.StockAccountStatus;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionEntitlement;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockInstrument;
import stock.back.service.database.entity.StockInstrumentReportEvent;
import stock.back.service.database.entity.StockInstrumentReportEventType;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.entity.StockPriceTick;
import stock.back.service.database.entity.StockVirtualMarketConfig;
import stock.back.service.database.repository.PortfolioSnapshotRepository;
import stock.back.service.database.repository.StockAccountCashFlowRepository;
import stock.back.service.database.repository.StockAccountRepository;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantProfileConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.database.repository.StockCorporateActionEntitlementRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockInstrumentReportEventRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
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
import stock.back.service.market.vo.AutoParticipantHoldingResponse;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentRequest;
import stock.back.service.market.vo.AutoParticipantCashAdjustmentResponse;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;
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
import stock.back.service.market.vo.ListingAutoAccountRequest;
import stock.back.service.market.vo.ListingAutoAccountResponse;
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
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class MarketService {

    private static final String LISTING_SUPPLY_USER_KEY_PREFIX = "stock-listing-";
    private static final int DEFAULT_RECURRING_DEPOSIT_INTERVAL_DAYS = 30;
    private static final Map<AutoParticipantProfileType, ProfileConfigDefaults> PROFILE_CONFIG_DEFAULTS = createProfileConfigDefaults();

    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockPriceTickRepository stockPriceTickRepository;
    private final StockOrderRepository stockOrderRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final StockAccountCashFlowRepository stockAccountCashFlowRepository;
    private final StockAccountRepository stockAccountRepository;
    private final StockPriceCacheService stockPriceCacheService;
    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockAutoParticipantProfileConfigRepository stockAutoParticipantProfileConfigRepository;
    private final StockAutoParticipantRepository stockAutoParticipantRepository;
    private final StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository;
    private final StockVirtualMarketConfigRepository stockVirtualMarketConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
    private final StockExecutionMarketViewRepository stockExecutionMarketViewRepository;
    private final StockCorporateActionRepository stockCorporateActionRepository;
    private final StockCorporateActionEntitlementRepository stockCorporateActionEntitlementRepository;
    private final StockInstrumentReportEventRepository stockInstrumentReportEventRepository;
    private final StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;
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
        seedListingAutoAccount(symbol, name, request.initialPrice(), request.issuedShares(), request.listingAutoAccount());
        return toOrderBookInstrumentResponse(instrument);
    }

    private void seedListingAutoAccount(
            String symbol,
            String name,
            BigDecimal initialPrice,
            long tradableShares,
            ListingAutoAccountRequest request
    ) {
        LocalDateTime now = LocalDateTime.now();
        String listingUserKey = LISTING_SUPPLY_USER_KEY_PREFIX + symbol.toLowerCase(Locale.ROOT);
        String displayName = normalizeText(request == null ? null : request.displayName());
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
                0L,
                initialPrice,
                now
        );
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(symbol, listingUserKey, displayName, tradableShares);
        if (request != null) {
            config.update(
                    displayName,
                    request.enabled(),
                    request.positionSide(),
                    request.maxOrderQuantity(),
                    request.orderTtlSeconds(),
                    request.priceOffsetTicks()
            );
            validateListingAutoAccountConfig(config);
        }
        stockListingAutoAccountConfigRepository.save(config);
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
        if (request.actionType() != StockCorporateActionType.DELISTING) {
            assertNoOpenOrderBookOrders(normalizedSymbol);
        }

        return switch (request.actionType()) {
            case PAID_IN_CAPITAL_INCREASE, ADDITIONAL_ISSUE -> applyShareIssue(instrument, request);
            case BONUS_ISSUE, STOCK_DIVIDEND -> applyFreeShareDistribution(instrument, request);
            case STOCK_SPLIT -> applyStockSplit(instrument, request);
            case CASH_DIVIDEND -> applyCashDividend(instrument, request);
            case DELISTING -> applyDelisting(instrument, request);
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
                normalizeOptionalText(request.riseReason()),
                normalizeOptionalText(request.fallReason()),
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
                normalizeOptionalText(request.riseReason()),
                normalizeOptionalText(request.fallReason()),
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
        List<AutoParticipantProfileConfigResponse> participantProfileConfigs = getAutoParticipantProfileConfigs();
        List<ListingAutoAccountResponse> listingAutoAccounts = stockListingAutoAccountConfigRepository.findAllByOrderBySymbolAsc().stream()
                .map(this::toListingAutoAccountResponse)
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
                participantSymbolConfigs,
                participantProfileConfigs,
                listingAutoAccounts
        );
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantProfileConfigResponse> getAutoParticipantProfileConfigs() {
        Map<AutoParticipantProfileType, StockAutoParticipantProfileConfig> savedConfigs = stockAutoParticipantProfileConfigRepository.findAllByOrderByProfileTypeAsc()
                .stream()
                .collect(Collectors.toMap(StockAutoParticipantProfileConfig::getProfileType, Function.identity()));
        return Arrays.stream(AutoParticipantProfileType.values())
                .map(profileType -> toAutoParticipantProfileConfigResponse(profileType, savedConfigs.get(profileType)))
                .toList();
    }

    @Transactional
    public AutoParticipantProfileConfigResponse updateAutoParticipantProfileConfig(
            String profileTypeValue,
            AutoParticipantProfileConfigRequest request
    ) {
        AutoParticipantProfileType profileType = parseAutoParticipantProfileType(profileTypeValue);
        if (request == null) {
            throw StockException.badRequest("Auto participant profile config update is required");
        }
        BigDecimal newsWeight = requireRatioValue(request.newsWeight(), "News weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal momentumWeight = requireRatioValue(request.momentumWeight(), "Momentum weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal contrarianWeight = requireRatioValue(request.contrarianWeight(), "Contrarian weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal lossAversionWeight = requireRatioValue(request.lossAversionWeight(), "Loss aversion weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal herdingWeight = requireRatioValue(request.herdingWeight(), "Herding weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal marketMakingWeight = requireRatioValue(request.marketMakingWeight(), "Market making weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal overconfidenceWeight = requireRatioValue(request.overconfidenceWeight(), "Overconfidence weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal noiseWeight = requireRatioValue(request.noiseWeight(), "Noise weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal panicSellWeight = requireRatioValue(request.panicSellWeight(), "Panic sell weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal dipBuyWeight = requireRatioValue(request.dipBuyWeight(), "Dip buy weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal orderMultiplier = requireRatioValue(request.orderMultiplier(), "Order multiplier", BigDecimal.ZERO, BigDecimal.valueOf(5));
        BigDecimal aggressionMultiplier = requireRatioValue(request.aggressionMultiplier(), "Aggression multiplier", BigDecimal.ZERO, BigDecimal.valueOf(5));
        BigDecimal orderTtlMultiplier = requireRatioValue(request.orderTtlMultiplier(), "Order TTL multiplier", new BigDecimal("0.1"), BigDecimal.TEN);
        BigDecimal quantityMultiplier = requireRatioValue(request.quantityMultiplier(), "Quantity multiplier", BigDecimal.ZERO, BigDecimal.valueOf(5));
        BigDecimal holdingPatienceWeight = requireRatioValue(request.holdingPatienceWeight(), "Holding patience weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal deepLossHoldWeight = requireRatioValue(request.deepLossHoldWeight(), "Deep loss hold weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal profitTakingWeight = requireRatioValue(request.profitTakingWeight(), "Profit taking weight", BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal recurringDepositAmount = requireRatioValue(request.recurringDepositAmount(), "Recurring deposit amount", BigDecimal.ZERO, new BigDecimal("1000000000000"));
        BigDecimal recurringDepositIntervalValue = normalizeRecurringCashIntervalValue(
                request.recurringDepositIntervalValue() == null && request.recurringDepositIntervalDays() != null
                        ? BigDecimal.valueOf(request.recurringDepositIntervalDays())
                        : request.recurringDepositIntervalValue(),
                recurringDepositAmount
        );
        RecurringCashIntervalUnit recurringDepositIntervalUnit = normalizeRecurringCashIntervalUnit(
                request.recurringDepositIntervalUnit() == null && request.recurringDepositIntervalDays() != null
                        ? RecurringCashIntervalUnit.DAY.name()
                        : request.recurringDepositIntervalUnit(),
                recurringDepositAmount
        );
        if (recurringDepositIntervalValue == null) {
            recurringDepositIntervalValue = BigDecimal.ZERO;
        }
        if (recurringDepositIntervalUnit == null) {
            recurringDepositIntervalUnit = RecurringCashIntervalUnit.DAY;
        }
        BigDecimal finalRecurringDepositIntervalValue = recurringDepositIntervalValue;
        RecurringCashIntervalUnit finalRecurringDepositIntervalUnit = recurringDepositIntervalUnit;
        StockAutoParticipantProfileConfig config = stockAutoParticipantProfileConfigRepository.findById(profileType)
                .orElseGet(() -> StockAutoParticipantProfileConfig.create(
                        profileType,
                        newsWeight,
                        momentumWeight,
                        contrarianWeight,
                        lossAversionWeight,
                        herdingWeight,
                        marketMakingWeight,
                        overconfidenceWeight,
                        noiseWeight,
                        panicSellWeight,
                        dipBuyWeight,
                        orderMultiplier,
                        aggressionMultiplier,
                        orderTtlMultiplier,
                        quantityMultiplier,
                        holdingPatienceWeight,
                        deepLossHoldWeight,
                        profitTakingWeight,
                        recurringDepositAmount,
                        finalRecurringDepositIntervalValue,
                        finalRecurringDepositIntervalUnit
                ));
        config.update(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                noiseWeight,
                panicSellWeight,
                dipBuyWeight,
                orderMultiplier,
                aggressionMultiplier,
                orderTtlMultiplier,
                quantityMultiplier,
                holdingPatienceWeight,
                deepLossHoldWeight,
                profitTakingWeight,
                recurringDepositAmount,
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit
        );
        return toAutoParticipantProfileConfigResponse(profileType, stockAutoParticipantProfileConfigRepository.save(config));
    }

    @Transactional
    public ListingAutoAccountResponse updateListingAutoAccountConfig(String symbol, ListingAutoAccountRequest request) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (request == null) {
            throw StockException.badRequest("Listing auto account update is required");
        }
        StockListingAutoAccountConfig config = stockListingAutoAccountConfigRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Listing auto account not found: " + normalizedSymbol));
        String displayName = request.displayName() == null ? null : normalizeText(request.displayName());
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
        validateListingAutoAccountConfig(config);
        return toListingAutoAccountResponse(config);
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantOverviewResponse> getAutoParticipantOverviews() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        String sql = """
                select p.user_key,
                       p.display_name,
                       p.enabled,
                       p.profile_type,
                       p.created_at,
                       p.updated_at,
                       p.withdrawn_at,
                       a.id as account_id,
                       a.status as account_status,
                       coalesce(a.cash_balance, 0) as available_cash,
                       coalesce((
                           select sum(o.reserved_cash)
                           from stock_order o
                           where o.account_id = a.id
                             and o.market_type = 'ORDER_BOOK'
                             and o.status in ('PENDING', 'PARTIALLY_FILLED')
                       ), 0) as reserved_buy_cash,
                       coalesce((
                           select sum(h.quantity * sp.current_price)
                           from stock_holding h
                           join stock_price sp on sp.symbol = h.symbol
                           where h.account_id = a.id
                             and h.quantity > 0
                       ), 0) as holding_market_value,
                       coalesce((
                           select sum(
	                               case
	                                   when f.flow_type = 'DEPOSIT' and f.reason <> 'DIVIDEND_PAYMENT' then f.amount
	                                   when f.flow_type = 'WITHDRAW' then -f.amount
	                                   else 0
	                               end
                           )
                           from stock_account_cash_flow f
                           where f.account_id = a.id
                       ), 0) as net_cash_flow,
                       coalesce((
                           select count(*)
                           from stock_holding h
                           where h.account_id = a.id
                             and h.quantity > 0
                       ), 0) as holding_count,
                       coalesce((
                           select sum(h.quantity)
                           from stock_holding h
                           where h.account_id = a.id
                       ), 0) as total_holding_quantity,
                       coalesce((
                           select sum(h.reserved_quantity)
                           from stock_holding h
                           where h.account_id = a.id
                       ), 0) as reserved_sell_quantity,
                       coalesce((
                           select count(*)
                           from stock_order o
                           where o.account_id = a.id
                             and o.market_type = 'ORDER_BOOK'
                             and o.status in ('PENDING', 'PARTIALLY_FILLED')
                       ), 0) as open_order_count,
                       coalesce((
                           select count(*)
                           from stock_order o
                           where o.account_id = a.id
                             and o.market_type = 'ORDER_BOOK'
                             and o.side = 'BUY'
                             and o.status in ('PENDING', 'PARTIALLY_FILLED')
                       ), 0) as open_buy_order_count,
                       coalesce((
                           select count(*)
                           from stock_order o
                           where o.account_id = a.id
                             and o.market_type = 'ORDER_BOOK'
                             and o.side = 'SELL'
                             and o.status in ('PENDING', 'PARTIALLY_FILLED')
                       ), 0) as open_sell_order_count,
                       coalesce((
                           select sum(o.quantity - o.filled_quantity)
                           from stock_order o
                           where o.account_id = a.id
                             and o.market_type = 'ORDER_BOOK'
                             and o.side = 'BUY'
                             and o.status in ('PENDING', 'PARTIALLY_FILLED')
                       ), 0) as open_buy_quantity,
                       coalesce((
                           select sum(o.quantity - o.filled_quantity)
                           from stock_order o
                           where o.account_id = a.id
                             and o.market_type = 'ORDER_BOOK'
                             and o.side = 'SELL'
                             and o.status in ('PENDING', 'PARTIALLY_FILLED')
                       ), 0) as open_sell_quantity,
                       coalesce((
                           select count(*)
                           from stock_execution e
                           where e.account_id = a.id
                             and e.source = 'INTERNAL_ORDER_BOOK'
                             and e.executed_at >= ?
                       ), 0) as today_execution_count,
                       coalesce((
                           select sum(case when e.side = 'BUY' then e.quantity else 0 end)
                           from stock_execution e
                           where e.account_id = a.id
                             and e.source = 'INTERNAL_ORDER_BOOK'
                             and e.executed_at >= ?
                       ), 0) as today_buy_quantity,
                       coalesce((
                           select sum(case when e.side = 'SELL' then e.quantity else 0 end)
                           from stock_execution e
                           where e.account_id = a.id
                             and e.source = 'INTERNAL_ORDER_BOOK'
                             and e.executed_at >= ?
                       ), 0) as today_sell_quantity,
                       coalesce((
                           select sum(e.gross_amount)
                           from stock_execution e
                           where e.account_id = a.id
                             and e.source = 'INTERNAL_ORDER_BOOK'
                             and e.executed_at >= ?
                       ), 0) as today_gross_amount,
                       coalesce((
                           select count(*)
                           from stock_auto_participant_symbol_config sc
                           where sc.user_key = p.user_key
                       ), 0) as strategy_count,
                       coalesce((
                           select count(*)
                           from stock_auto_participant_symbol_config sc
                           where sc.user_key = p.user_key
                             and sc.enabled = true
                       ), 0) as enabled_strategy_count,
                       (
                           select max(o.created_at)
                           from stock_order o
                           where o.account_id = a.id
                             and o.market_type = 'ORDER_BOOK'
                       ) as last_order_at,
                       (
                           select max(e.executed_at)
                           from stock_execution e
                           where e.account_id = a.id
                             and e.source = 'INTERNAL_ORDER_BOOK'
                       ) as last_execution_at
                from stock_auto_participant p
                left join stock_account a on a.user_key = p.user_key
                where p.withdrawn_at is null
                order by p.user_key asc
                """;
        List<AutoParticipantOverviewResponse> overviews = jdbcTemplate.query(sql, (rs, rowNum) -> {
            BigDecimal availableCash = nonNullDecimal(rs.getBigDecimal("available_cash"));
            BigDecimal reservedBuyCash = nonNullDecimal(rs.getBigDecimal("reserved_buy_cash"));
            BigDecimal holdingMarketValue = nonNullDecimal(rs.getBigDecimal("holding_market_value"));
            BigDecimal estimatedTotalAsset = availableCash.add(reservedBuyCash).add(holdingMarketValue);
            BigDecimal netCashFlow = nonNullDecimal(rs.getBigDecimal("net_cash_flow"));
            BigDecimal totalProfit = BigDecimal.ZERO;
            BigDecimal returnRate = BigDecimal.ZERO;
            if (netCashFlow.compareTo(BigDecimal.ZERO) > 0) {
                totalProfit = estimatedTotalAsset.subtract(netCashFlow);
                returnRate = totalProfit
                        .multiply(BigDecimal.valueOf(100))
                        .divide(netCashFlow, 4, RoundingMode.HALF_UP);
            }
            return new AutoParticipantOverviewResponse(
                    rs.getString("user_key"),
                    rs.getString("display_name"),
                    rs.getBoolean("enabled"),
                    rs.getString("profile_type"),
                    rs.getObject("account_id", Long.class),
                    rs.getString("account_status"),
                    availableCash,
                    reservedBuyCash,
                    holdingMarketValue,
                    estimatedTotalAsset,
                    netCashFlow,
                    totalProfit,
                    returnRate,
                    rs.getLong("holding_count"),
                    rs.getLong("total_holding_quantity"),
                    rs.getLong("reserved_sell_quantity"),
                    List.of(),
                    rs.getLong("open_order_count"),
                    rs.getLong("open_buy_order_count"),
                    rs.getLong("open_sell_order_count"),
                    rs.getLong("open_buy_quantity"),
                    rs.getLong("open_sell_quantity"),
                    rs.getLong("today_execution_count"),
                    rs.getLong("today_buy_quantity"),
                    rs.getLong("today_sell_quantity"),
                    nonNullDecimal(rs.getBigDecimal("today_gross_amount")),
                    rs.getLong("strategy_count"),
                    rs.getLong("enabled_strategy_count"),
                    rs.getObject("last_order_at", LocalDateTime.class),
                    rs.getObject("last_execution_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class),
                    rs.getObject("withdrawn_at", LocalDateTime.class)
            );
        }, todayStart, todayStart, todayStart, todayStart);
        Map<Long, List<AutoParticipantHoldingResponse>> holdingsByAccountId = findAutoParticipantHoldings(overviews.stream()
                .map(AutoParticipantOverviewResponse::accountId)
                .filter(accountId -> accountId != null)
                .distinct()
                .toList());
        return overviews.stream()
                .map(overview -> withAutoParticipantHoldings(
                        overview,
                        overview.accountId() == null ? List.of() : holdingsByAccountId.getOrDefault(overview.accountId(), List.of())
                ))
                .toList();
    }

    private Map<Long, List<AutoParticipantHoldingResponse>> findAutoParticipantHoldings(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = accountIds.stream()
                .map(accountId -> "?")
                .collect(Collectors.joining(", "));
        String sql = """
                select h.account_id,
                       h.symbol,
                       h.quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       case
                           when h.quantity - coalesce(h.reserved_quantity, 0) > 0 then h.quantity - coalesce(h.reserved_quantity, 0)
                           else 0
                       end as available_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(sp.current_price, h.average_price, 0) as current_price,
                       coalesce(sp.current_price, h.average_price, 0) * h.quantity as market_value,
                       (coalesce(sp.current_price, h.average_price, 0) - coalesce(h.average_price, 0)) * h.quantity as unrealized_profit
                from stock_holding h
                left join stock_price sp on sp.symbol = h.symbol
                where h.account_id in (%s)
                  and (h.quantity > 0 or coalesce(h.reserved_quantity, 0) > 0)
                order by h.account_id asc, h.symbol asc
                """.formatted(placeholders);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AutoParticipantHoldingLedger(
                        rs.getLong("account_id"),
                        new AutoParticipantHoldingResponse(
                                rs.getString("symbol"),
                                rs.getLong("quantity"),
                                rs.getLong("reserved_quantity"),
                                rs.getLong("available_quantity"),
                                nonNullDecimal(rs.getBigDecimal("average_price")),
                                nonNullDecimal(rs.getBigDecimal("current_price")),
                                nonNullDecimal(rs.getBigDecimal("market_value")),
                                nonNullDecimal(rs.getBigDecimal("unrealized_profit"))
                        )
                ),
                accountIds.toArray()
        ).stream().collect(Collectors.groupingBy(
                AutoParticipantHoldingLedger::accountId,
                Collectors.mapping(AutoParticipantHoldingLedger::holding, Collectors.toList())
        ));
    }

    private AutoParticipantOverviewResponse withAutoParticipantHoldings(
            AutoParticipantOverviewResponse overview,
            List<AutoParticipantHoldingResponse> holdings
    ) {
        return new AutoParticipantOverviewResponse(
                overview.userKey(),
                overview.displayName(),
                overview.enabled(),
                overview.profileType(),
                overview.accountId(),
                overview.accountStatus(),
                overview.availableCash(),
                overview.reservedBuyCash(),
                overview.holdingMarketValue(),
                overview.estimatedTotalAsset(),
                overview.netCashFlow(),
                overview.totalProfit(),
                overview.returnRate(),
                overview.holdingCount(),
                overview.totalHoldingQuantity(),
                overview.reservedSellQuantity(),
                holdings,
                overview.openOrderCount(),
                overview.openBuyOrderCount(),
                overview.openSellOrderCount(),
                overview.openBuyQuantity(),
                overview.openSellQuantity(),
                overview.todayExecutionCount(),
                overview.todayBuyQuantity(),
                overview.todaySellQuantity(),
                overview.todayGrossAmount(),
                overview.strategyCount(),
                overview.enabledStrategyCount(),
                overview.lastOrderAt(),
                overview.lastExecutionAt(),
                overview.createdAt(),
                overview.updatedAt(),
                overview.withdrawnAt()
        );
    }

    private record AutoParticipantHoldingLedger(
            Long accountId,
            AutoParticipantHoldingResponse holding
    ) {
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
        AutoParticipantProfileType profileType = parseAutoParticipantProfileType(request == null ? null : request.profileType());
        BigDecimal recurringCashAmount = normalizeRecurringCashAmount(request == null ? null : request.recurringCashAmount());
        BigDecimal recurringCashIntervalValue = normalizeRecurringCashIntervalValue(
                request == null ? null : request.recurringCashIntervalValue(),
                recurringCashAmount
        );
        RecurringCashIntervalUnit recurringCashIntervalUnit = normalizeRecurringCashIntervalUnit(
                request == null ? null : request.recurringCashIntervalUnit(),
                recurringCashAmount
        );
        StockAutoParticipant participant = stockAutoParticipantRepository.findById(normalizedUserKey)
                .map(existing -> {
                    existing.update(
                            displayName,
                            request == null ? null : request.enabled(),
                            profileType,
                            recurringCashAmount,
                            recurringCashIntervalValue,
                            recurringCashIntervalUnit
                    );
                    return existing;
                })
                .orElseGet(() -> {
                    return StockAutoParticipant.create(
                        normalizedUserKey,
                        displayName,
                        request == null || request.enabled() == null || request.enabled(),
                        profileType,
                        recurringCashAmount,
                        recurringCashIntervalValue,
                        recurringCashIntervalUnit
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

    private String normalizeOptionalText(String value) {
        String normalized = normalizeText(value);
        return normalized.isBlank() ? null : normalized;
    }

    private BigDecimal requireRatioValue(BigDecimal value, String fieldName, BigDecimal min, BigDecimal max) {
        if (value == null) {
            throw StockException.badRequest(fieldName + " is required");
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw StockException.badRequest(fieldName + " must be between " + min + " and " + max);
        }
        return value;
    }

    private Integer requireIntValue(Integer value, String fieldName, int min, int max) {
        if (value == null) {
            throw StockException.badRequest(fieldName + " is required");
        }
        if (value < min || value > max) {
            throw StockException.badRequest(fieldName + " must be between " + min + " and " + max);
        }
        return value;
    }

    private BigDecimal normalizeRecurringCashAmount(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("1000000000000")) > 0) {
            throw StockException.badRequest("Recurring cash amount must be between 0 and 1000000000000");
        }
        return value;
    }

    private BigDecimal normalizeRecurringCashIntervalValue(BigDecimal value, BigDecimal amount) {
        if (amount == null) {
            return value == null ? null : requireRecurringCashIntervalValue(value);
        }
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return value == null ? BigDecimal.ZERO : requireRecurringCashIntervalValue(value);
        }
        if (value == null) {
            throw StockException.badRequest("Recurring cash interval value is required when recurring cash amount is positive");
        }
        BigDecimal interval = requireRecurringCashIntervalValue(value);
        if (interval.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Recurring cash interval value must be greater than 0 when recurring cash amount is positive");
        }
        return interval;
    }

    private BigDecimal requireRecurringCashIntervalValue(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("1000")) > 0) {
            throw StockException.badRequest("Recurring cash interval value must be between 0 and 1000");
        }
        return value;
    }

    private RecurringCashIntervalUnit normalizeRecurringCashIntervalUnit(String value, BigDecimal amount) {
        String normalized = normalizeText(value);
        if (amount == null && normalized.isBlank()) {
            return null;
        }
        if (amount != null && amount.compareTo(BigDecimal.ZERO) == 0 && normalized.isBlank()) {
            return null;
        }
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0 && normalized.isBlank()) {
            throw StockException.badRequest("Recurring cash interval unit is required when recurring cash amount is positive");
        }
        try {
            return RecurringCashIntervalUnit.parseOrDefault(normalized);
        } catch (IllegalArgumentException exception) {
            throw StockException.badRequest("Unknown recurring cash interval unit");
        }
    }

    private AutoParticipantProfileType parseAutoParticipantProfileType(String value) {
        try {
            return AutoParticipantProfileType.parseOrDefault(value);
        } catch (IllegalArgumentException exception) {
            throw StockException.badRequest("Unknown auto participant profile type");
        }
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
    }

    private void validateListingAutoAccountConfig(StockListingAutoAccountConfig config) {
        if (config.getPositionSide() == null) {
            throw StockException.badRequest("Listing auto account position side is required");
        }
        if (config.getPositionSide() != ListingAutoPosition.SELL_ONLY && config.getPositionSide() != ListingAutoPosition.BUY_ONLY) {
            throw StockException.badRequest("Listing auto account position side must be SELL_ONLY or BUY_ONLY");
        }
        if (config.getMaxOrderQuantity() == null || config.getMaxOrderQuantity() <= 0) {
            throw StockException.badRequest("Listing auto account max order quantity must be positive");
        }
        if (config.getOrderTtlSeconds() == null || config.getOrderTtlSeconds() <= 0) {
            throw StockException.badRequest("Listing auto account order TTL seconds must be positive");
        }
        if (config.getPriceOffsetTicks() == null || config.getPriceOffsetTicks() < 0) {
            throw StockException.badRequest("Listing auto account price offset ticks must be zero or positive");
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
                rejectPresent(request.delistingDate(), "Paid-in capital increase does not use delistingDate");
            }
            case ADDITIONAL_ISSUE -> {
                rejectPresent(request.splitFrom(), "Additional issue does not use splitFrom");
                rejectPresent(request.splitTo(), "Additional issue does not use splitTo");
                rejectPresent(request.exRightsDate(), "Additional issue does not use exRightsDate");
                rejectPresent(request.paymentDate(), "Additional issue does not use paymentDate");
                rejectPresent(request.dividendAmount(), "Additional issue does not use dividendAmount");
                rejectPresent(request.delistingDate(), "Additional issue does not use delistingDate");
            }
            case STOCK_SPLIT -> {
                rejectPresent(request.shareQuantity(), "Stock split does not use shareQuantity");
                rejectPresent(request.issuePrice(), "Stock split does not use issuePrice");
                rejectPresent(request.exRightsDate(), "Stock split does not use exRightsDate");
                rejectPresent(request.paymentDate(), "Stock split does not use paymentDate");
                rejectPresent(request.dividendAmount(), "Stock split does not use dividendAmount");
                rejectPresent(request.delistingDate(), "Stock split does not use delistingDate");
            }
            case CASH_DIVIDEND -> {
                rejectPresent(request.shareQuantity(), "Cash dividend does not use shareQuantity");
                rejectPresent(request.issuePrice(), "Cash dividend does not use issuePrice");
                rejectPresent(request.splitFrom(), "Cash dividend does not use splitFrom");
                rejectPresent(request.splitTo(), "Cash dividend does not use splitTo");
                rejectPresent(request.listingDate(), "Cash dividend does not use listingDate");
                rejectPresent(request.delistingDate(), "Cash dividend does not use delistingDate");
            }
            case BONUS_ISSUE, STOCK_DIVIDEND -> {
                rejectPresent(request.issuePrice(), "Free share distribution does not use issuePrice");
                rejectPresent(request.splitFrom(), "Free share distribution does not use splitFrom");
                rejectPresent(request.splitTo(), "Free share distribution does not use splitTo");
                rejectPresent(request.paymentDate(), "Free share distribution does not use paymentDate");
                rejectPresent(request.dividendAmount(), "Free share distribution does not use dividendAmount");
                rejectPresent(request.delistingDate(), "Free share distribution does not use delistingDate");
            }
            case DELISTING -> {
                rejectPresent(request.shareQuantity(), "Delisting does not use shareQuantity");
                rejectPresent(request.issuePrice(), "Delisting does not use issuePrice");
                rejectPresent(request.splitFrom(), "Delisting does not use splitFrom");
                rejectPresent(request.splitTo(), "Delisting does not use splitTo");
                rejectPresent(request.exRightsDate(), "Delisting does not use exRightsDate");
                rejectPresent(request.paymentDate(), "Delisting does not use paymentDate");
                rejectPresent(request.listingDate(), "Delisting does not use listingDate");
                rejectPresent(request.dividendAmount(), "Delisting does not use dividendAmount");
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

    private OrderBookInstrumentResponse applyDelisting(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        LocalDate delistingDate = request.delistingDate();
        if (delistingDate == null) {
            throw StockException.badRequest("Delisting requires a delisting date");
        }
        stockCorporateActionRepository.save(StockCorporateAction.delisting(
                instrument.getSymbol(),
                delistingDate,
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

    private ListingAutoAccountResponse toListingAutoAccountResponse(StockListingAutoAccountConfig config) {
        ListingAutoAccountLedger ledger = findListingAutoAccountLedger(config);
        return new ListingAutoAccountResponse(
                config.getSymbol(),
                config.getUserKey(),
                config.getDisplayName(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getPositionSide(),
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

    private ListingAutoAccountLedger findListingAutoAccountLedger(StockListingAutoAccountConfig config) {
        return jdbcTemplate.query(
                """
                select a.id as account_id,
                       coalesce(a.cash_balance, 0) as cash_balance,
                       coalesce(h.quantity, 0) as holding_quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(p.current_price, 0) as current_price
                from stock_account a
                left join stock_holding h on h.account_id = a.id and h.symbol = ?
                left join stock_price p on p.symbol = ?
                where a.user_key = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return ListingAutoAccountLedger.empty();
                    }
                    long holdingQuantity = Math.max(0L, rs.getLong("holding_quantity"));
                    long reservedQuantity = Math.max(0L, rs.getLong("reserved_quantity"));
                    BigDecimal cashBalance = nonNullMoney(rs.getBigDecimal("cash_balance"));
                    BigDecimal averagePrice = nonNullMoney(rs.getBigDecimal("average_price"));
                    BigDecimal currentPrice = nonNullMoney(rs.getBigDecimal("current_price"));
                    return ListingAutoAccountLedger.of(
                            rs.getLong("account_id"),
                            cashBalance,
                            holdingQuantity,
                            reservedQuantity,
                            averagePrice,
                            currentPrice
                    );
                },
                config.getSymbol(),
                config.getSymbol(),
                config.getUserKey()
        );
    }

    private BigDecimal nonNullMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ListingAutoAccountLedger(
            Long accountId,
            BigDecimal cashBalance,
            long holdingQuantity,
            long reservedQuantity,
            long availableQuantity,
            BigDecimal averagePrice,
            BigDecimal currentPrice,
            BigDecimal marketValue
    ) {

        private static ListingAutoAccountLedger empty() {
            return new ListingAutoAccountLedger(
                    null,
                    BigDecimal.ZERO,
                    0L,
                    0L,
                    0L,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            );
        }

        private static ListingAutoAccountLedger of(
                Long accountId,
                BigDecimal cashBalance,
                long holdingQuantity,
                long reservedQuantity,
                BigDecimal averagePrice,
                BigDecimal currentPrice
        ) {
            long availableQuantity = Math.max(0L, holdingQuantity - reservedQuantity);
            BigDecimal marketValue = currentPrice.multiply(BigDecimal.valueOf(holdingQuantity));
            return new ListingAutoAccountLedger(
                    accountId,
                    cashBalance,
                    holdingQuantity,
                    reservedQuantity,
                    availableQuantity,
                    averagePrice,
                    currentPrice,
                    marketValue
            );
        }
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
        var cachedPrice = price.getCurrentPrice().compareTo(BigDecimal.ZERO) <= 0
                ? Optional.<CachedStockPrice>empty()
                : stockPriceCacheService.getCachedPrice(price.getSymbol());
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
                action.getDelistingDate(),
                action.getDelistingTreatment() == null ? null : action.getDelistingTreatment().name(),
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
                participant.getProfileType() == null
                        ? AutoParticipantProfileType.defaultType().name()
                        : participant.getProfileType().name(),
                participant.getRecurringCashAmount(),
                participant.getRecurringCashIntervalValue(),
                participant.getRecurringCashIntervalUnit() == null ? null : participant.getRecurringCashIntervalUnit().name(),
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

    private BigDecimal nonNullDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Map<AutoParticipantProfileType, ProfileConfigDefaults> createProfileConfigDefaults() {
        Map<AutoParticipantProfileType, ProfileConfigDefaults> defaults = new EnumMap<>(AutoParticipantProfileType.class);
        defaults.put(AutoParticipantProfileType.NEWS_REACTIVE, profileDefaults(0.85, 0.15, 0.00, 0.25, 0.20, 0.00, 0.10, 0.10, 0.00, 0.05, 1.10, 1.15, 1.00, 1.00, 0.15, 0.10, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.MOMENTUM_FOLLOWER, profileDefaults(0.25, 0.85, 0.00, 0.20, 0.35, 0.00, 0.15, 0.15, 0.05, 0.00, 1.20, 1.25, 0.80, 1.00, 0.10, 0.05, 0.25, "0.00"));
        defaults.put(AutoParticipantProfileType.CONTRARIAN, profileDefaults(0.20, 0.00, 0.85, 0.25, 0.00, 0.10, 0.05, 0.12, 0.00, 0.35, 1.00, 0.90, 1.20, 1.00, 0.20, 0.15, 0.35, "0.00"));
        defaults.put(AutoParticipantProfileType.LOSS_AVERSE, profileDefaults(0.25, 0.10, 0.00, 0.95, 0.10, 0.00, 0.05, 0.08, 0.05, 0.00, 0.85, 0.80, 1.80, 0.80, 0.75, 0.60, 0.05, "0.00"));
        defaults.put(AutoParticipantProfileType.OVERCONFIDENT, profileDefaults(0.35, 0.45, 0.00, 0.20, 0.25, 0.00, 0.95, 0.20, 0.05, 0.05, 1.60, 1.35, 0.70, 1.25, 0.10, 0.05, 0.10, "0.00"));
        defaults.put(AutoParticipantProfileType.HERD_FOLLOWER, profileDefaults(0.25, 0.25, 0.00, 0.15, 0.90, 0.00, 0.15, 0.15, 0.15, 0.00, 1.25, 1.20, 0.80, 1.00, 0.05, 0.00, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.MARKET_MAKER, profileDefaults(0.15, 0.05, 0.00, 0.10, 0.10, 0.95, 0.00, 0.08, 0.00, 0.00, 1.25, 0.65, 0.60, 1.00, 0.00, 0.00, 0.45, "0.00"));
        defaults.put(AutoParticipantProfileType.NOISE_TRADER, profileDefaults(0.35, 0.20, 0.10, 0.20, 0.15, 0.00, 0.10, 0.45, 0.05, 0.05, 1.00, 1.00, 1.00, 1.00, 0.10, 0.05, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.VALUE_ANCHOR, profileDefaults(0.20, 0.00, 0.45, 0.55, 0.00, 0.10, 0.00, 0.08, 0.00, 0.25, 0.80, 0.75, 1.60, 0.80, 0.50, 0.35, 0.15, "0.00"));
        defaults.put(AutoParticipantProfileType.SCALPER, profileDefaults(0.25, 0.60, 0.00, 0.10, 0.40, 0.00, 0.25, 0.35, 0.10, 0.00, 2.00, 1.50, 0.25, 0.70, 0.00, 0.00, 0.90, "0.00"));
        defaults.put(AutoParticipantProfileType.DAY_TRADER, profileDefaults(0.25, 0.70, 0.00, 0.08, 0.35, 0.00, 0.25, 0.30, 0.15, 0.00, 2.30, 1.60, 0.35, 0.90, 0.00, 0.00, 0.85, "0.00"));
        defaults.put(AutoParticipantProfileType.SWING_TRADER, profileDefaults(0.30, 0.45, 0.25, 0.25, 0.15, 0.00, 0.15, 0.12, 0.05, 0.20, 0.95, 1.05, 1.10, 1.05, 0.20, 0.15, 0.45, "0.00"));
        defaults.put(AutoParticipantProfileType.LONG_TERM_HOLDER, profileDefaults(0.20, 0.05, 0.20, 0.85, 0.00, 0.00, 0.00, 0.05, 0.00, 0.45, 0.45, 0.50, 2.50, 0.55, 0.95, 0.75, 0.05, "0.00"));
        defaults.put(AutoParticipantProfileType.PAYDAY_ACCUMULATOR, profileDefaults(0.20, 0.10, 0.15, 0.65, 0.05, 0.00, 0.00, 0.06, 0.00, 0.55, 0.90, 0.80, 2.00, 0.70, 0.90, 0.55, 0.05, "300000.00"));
        defaults.put(AutoParticipantProfileType.DIVIDEND_REINVESTOR, profileDefaults(0.20, 0.08, 0.20, 0.70, 0.05, 0.00, 0.00, 0.05, 0.00, 0.50, 0.80, 0.75, 2.20, 0.65, 0.90, 0.65, 0.08, "120000.00"));
        defaults.put(AutoParticipantProfileType.LIMIT_DOWN_TRAPPED, profileDefaults(0.20, 0.00, 0.20, 1.00, 0.05, 0.00, 0.00, 0.08, 0.00, 0.25, 0.55, 0.55, 2.50, 0.50, 1.00, 1.00, 0.00, "0.00"));
        defaults.put(AutoParticipantProfileType.AVERAGE_DOWN_BUYER, profileDefaults(0.20, 0.00, 0.55, 0.80, 0.05, 0.00, 0.00, 0.08, 0.00, 0.95, 1.05, 0.90, 1.80, 1.20, 0.75, 0.35, 0.05, "0.00"));
        defaults.put(AutoParticipantProfileType.STOP_LOSS_TRADER, profileDefaults(0.25, 0.35, 0.00, 0.00, 0.20, 0.00, 0.05, 0.18, 0.80, 0.00, 1.20, 1.25, 0.55, 0.95, 0.00, 0.10, 0.65, "0.00"));
        defaults.put(AutoParticipantProfileType.FOMO_BUYER, profileDefaults(0.35, 0.95, 0.00, 0.05, 0.75, 0.00, 0.45, 0.28, 0.05, 0.00, 1.65, 1.55, 0.45, 1.15, 0.05, 0.00, 0.30, "0.00"));
        defaults.put(AutoParticipantProfileType.PANIC_SELLER, profileDefaults(0.25, 0.25, 0.00, 0.20, 0.45, 0.00, 0.10, 0.25, 0.90, 0.00, 1.40, 1.40, 0.45, 1.10, 0.00, 0.00, 0.70, "0.00"));
        defaults.put(AutoParticipantProfileType.DIP_BUYER, profileDefaults(0.25, 0.00, 0.65, 0.35, 0.10, 0.00, 0.05, 0.15, 0.00, 0.90, 1.15, 1.05, 0.80, 1.00, 0.25, 0.25, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.PROFIT_LOCKER, profileDefaults(0.20, 0.35, 0.00, 0.10, 0.15, 0.00, 0.05, 0.20, 0.05, 0.00, 1.35, 1.25, 0.55, 0.85, 0.00, 0.95, 1.00, "0.00"));
        defaults.put(AutoParticipantProfileType.LIQUIDITY_AVOIDANT, profileDefaults(0.20, 0.10, 0.00, 0.35, 0.00, 0.00, 0.00, 0.05, 0.10, 0.00, 0.55, 0.55, 1.80, 0.60, 0.25, 0.10, 0.35, "0.00"));
        defaults.put(AutoParticipantProfileType.CASH_DEFENSIVE, profileDefaults(0.15, 0.05, 0.15, 0.50, 0.00, 0.00, 0.00, 0.04, 0.10, 0.10, 0.35, 0.45, 2.20, 0.35, 0.70, 0.20, 0.20, "0.00"));
        defaults.put(AutoParticipantProfileType.WHALE, profileDefaults(0.30, 0.35, 0.00, 0.20, 0.25, 0.00, 0.20, 0.10, 0.05, 0.00, 1.20, 0.85, 1.20, 1.80, 0.05, 0.00, 0.30, "0.00"));
        defaults.put(AutoParticipantProfileType.SMALL_DIVERSIFIER, profileDefaults(0.25, 0.20, 0.10, 0.30, 0.10, 0.00, 0.05, 0.12, 0.00, 0.05, 1.45, 0.70, 1.00, 0.45, 0.25, 0.15, 0.25, "0.00"));
        defaults.put(AutoParticipantProfileType.OBSERVER, profileDefaults(0.15, 0.10, 0.00, 0.20, 0.00, 0.00, 0.00, 0.03, 0.00, 0.00, 0.30, 0.40, 2.20, 0.40, 0.10, 0.00, 0.10, "0.00"));
        return Map.copyOf(defaults);
    }

    private static ProfileConfigDefaults profileDefaults(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double noiseWeight,
            double panicSellWeight,
            double dipBuyWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double orderTtlMultiplier,
            double quantityMultiplier,
            double holdingPatienceWeight,
            double deepLossHoldWeight,
            double profitTakingWeight,
            String recurringDepositAmount
    ) {
        return profileDefaults(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                noiseWeight,
                panicSellWeight,
                dipBuyWeight,
                orderMultiplier,
                aggressionMultiplier,
                orderTtlMultiplier,
                quantityMultiplier,
                holdingPatienceWeight,
                deepLossHoldWeight,
                profitTakingWeight,
                recurringDepositAmount,
                BigDecimal.valueOf(DEFAULT_RECURRING_DEPOSIT_INTERVAL_DAYS),
                RecurringCashIntervalUnit.DAY
        );
    }

    private static ProfileConfigDefaults profileDefaults(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double noiseWeight,
            double panicSellWeight,
            double dipBuyWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double orderTtlMultiplier,
            double quantityMultiplier,
            double holdingPatienceWeight,
            double deepLossHoldWeight,
            double profitTakingWeight,
            String recurringDepositAmount,
            Integer recurringDepositIntervalDays
    ) {
        return profileDefaults(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                noiseWeight,
                panicSellWeight,
                dipBuyWeight,
                orderMultiplier,
                aggressionMultiplier,
                orderTtlMultiplier,
                quantityMultiplier,
                holdingPatienceWeight,
                deepLossHoldWeight,
                profitTakingWeight,
                recurringDepositAmount,
                BigDecimal.valueOf(recurringDepositIntervalDays),
                RecurringCashIntervalUnit.DAY
        );
    }

    private static ProfileConfigDefaults profileDefaults(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double noiseWeight,
            double panicSellWeight,
            double dipBuyWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double orderTtlMultiplier,
            double quantityMultiplier,
            double holdingPatienceWeight,
            double deepLossHoldWeight,
            double profitTakingWeight,
            String recurringDepositAmount,
            BigDecimal recurringDepositIntervalValue,
            RecurringCashIntervalUnit recurringDepositIntervalUnit
    ) {
        return new ProfileConfigDefaults(
                BigDecimal.valueOf(newsWeight),
                BigDecimal.valueOf(momentumWeight),
                BigDecimal.valueOf(contrarianWeight),
                BigDecimal.valueOf(lossAversionWeight),
                BigDecimal.valueOf(herdingWeight),
                BigDecimal.valueOf(marketMakingWeight),
                BigDecimal.valueOf(overconfidenceWeight),
                BigDecimal.valueOf(noiseWeight),
                BigDecimal.valueOf(panicSellWeight),
                BigDecimal.valueOf(dipBuyWeight),
                BigDecimal.valueOf(orderMultiplier),
                BigDecimal.valueOf(aggressionMultiplier),
                BigDecimal.valueOf(orderTtlMultiplier),
                BigDecimal.valueOf(quantityMultiplier),
                BigDecimal.valueOf(holdingPatienceWeight),
                BigDecimal.valueOf(deepLossHoldWeight),
                BigDecimal.valueOf(profitTakingWeight),
                new BigDecimal(recurringDepositAmount),
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit
        );
    }

    private record ProfileConfigDefaults(
            BigDecimal newsWeight,
            BigDecimal momentumWeight,
            BigDecimal contrarianWeight,
            BigDecimal lossAversionWeight,
            BigDecimal herdingWeight,
            BigDecimal marketMakingWeight,
            BigDecimal overconfidenceWeight,
            BigDecimal noiseWeight,
            BigDecimal panicSellWeight,
            BigDecimal dipBuyWeight,
            BigDecimal orderMultiplier,
            BigDecimal aggressionMultiplier,
            BigDecimal orderTtlMultiplier,
            BigDecimal quantityMultiplier,
            BigDecimal holdingPatienceWeight,
            BigDecimal deepLossHoldWeight,
            BigDecimal profitTakingWeight,
            BigDecimal recurringDepositAmount,
            BigDecimal recurringDepositIntervalValue,
            RecurringCashIntervalUnit recurringDepositIntervalUnit
    ) {
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

    private AutoParticipantProfileConfigResponse toAutoParticipantProfileConfigResponse(
            AutoParticipantProfileType profileType,
            StockAutoParticipantProfileConfig savedConfig
    ) {
        ProfileConfigDefaults defaults = PROFILE_CONFIG_DEFAULTS.getOrDefault(profileType, PROFILE_CONFIG_DEFAULTS.get(AutoParticipantProfileType.defaultType()));
        if (savedConfig == null) {
            return new AutoParticipantProfileConfigResponse(
                    profileType.name(),
                    defaults.newsWeight(),
                    defaults.momentumWeight(),
                    defaults.contrarianWeight(),
                    defaults.lossAversionWeight(),
                    defaults.herdingWeight(),
                    defaults.marketMakingWeight(),
                    defaults.overconfidenceWeight(),
                    defaults.noiseWeight(),
                    defaults.panicSellWeight(),
                    defaults.dipBuyWeight(),
                    defaults.orderMultiplier(),
                    defaults.aggressionMultiplier(),
                    defaults.orderTtlMultiplier(),
                    defaults.quantityMultiplier(),
                    defaults.holdingPatienceWeight(),
                    defaults.deepLossHoldWeight(),
                    defaults.profitTakingWeight(),
                    defaults.recurringDepositAmount(),
                    defaults.recurringDepositIntervalValue(),
                    defaults.recurringDepositIntervalUnit().name(),
                    recurringIntervalDays(defaults.recurringDepositIntervalValue(), defaults.recurringDepositIntervalUnit()),
                    false,
                    null
            );
        }
        BigDecimal recurringDepositIntervalValue = valueOrDefault(
                savedConfig.getRecurringDepositIntervalValue(),
                defaults.recurringDepositIntervalValue()
        );
        RecurringCashIntervalUnit recurringDepositIntervalUnit = savedConfig.getRecurringDepositIntervalUnit() == null
                ? defaults.recurringDepositIntervalUnit()
                : savedConfig.getRecurringDepositIntervalUnit();
        return new AutoParticipantProfileConfigResponse(
                profileType.name(),
                valueOrDefault(savedConfig.getNewsWeight(), defaults.newsWeight()),
                valueOrDefault(savedConfig.getMomentumWeight(), defaults.momentumWeight()),
                valueOrDefault(savedConfig.getContrarianWeight(), defaults.contrarianWeight()),
                valueOrDefault(savedConfig.getLossAversionWeight(), defaults.lossAversionWeight()),
                valueOrDefault(savedConfig.getHerdingWeight(), defaults.herdingWeight()),
                valueOrDefault(savedConfig.getMarketMakingWeight(), defaults.marketMakingWeight()),
                valueOrDefault(savedConfig.getOverconfidenceWeight(), defaults.overconfidenceWeight()),
                valueOrDefault(savedConfig.getNoiseWeight(), defaults.noiseWeight()),
                valueOrDefault(savedConfig.getPanicSellWeight(), defaults.panicSellWeight()),
                valueOrDefault(savedConfig.getDipBuyWeight(), defaults.dipBuyWeight()),
                savedConfig.getOrderMultiplier(),
                savedConfig.getAggressionMultiplier(),
                savedConfig.getOrderTtlMultiplier(),
                savedConfig.getQuantityMultiplier(),
                savedConfig.getHoldingPatienceWeight(),
                savedConfig.getDeepLossHoldWeight(),
                savedConfig.getProfitTakingWeight(),
                savedConfig.getRecurringDepositAmount(),
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit.name(),
                savedConfig.getRecurringDepositIntervalDays(),
                true,
                savedConfig.getUpdatedAt()
        );
    }

    private BigDecimal valueOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Integer recurringIntervalDays(BigDecimal value, RecurringCashIntervalUnit unit) {
        if (!RecurringCashIntervalUnit.DAY.equals(unit)) {
            return 1;
        }
        return value.setScale(0, RoundingMode.CEILING).intValue();
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
