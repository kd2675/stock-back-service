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
import stock.back.service.market.vo.InitialIssueAllocationRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;
import stock.back.service.market.vo.OrderBookInstrumentTradingRulesRequest;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class OrderBookInstrumentCommandService {

    private static final String LISTING_SUPPLY_USER_KEY_PREFIX = "stock-listing-";
    private static final String ROLE_SEPARATED_MODE = "SCALED_ROLE_SEPARATED";
    private static final String LEGACY_FULL_FLOAT_MODE = "LEGACY_FULL_FLOAT";
    private static final BigDecimal DEFAULT_TRADABLE_SHARE_RATE = new BigDecimal("0.500000");
    private static final BigDecimal MIN_TRADABLE_SHARE_RATE = new BigDecimal("0.200000");
    private static final BigDecimal MAX_TRADABLE_SHARE_RATE = new BigDecimal("0.850000");
    private static final String DEFAULT_UNDERWRITER_PARTICIPANT_CODE =
            "DEFAULT_ISSUE_UNDERWRITER";
    private static final String DEFAULT_UNDERWRITER_SELF_TRADE_GROUP =
            "ISSUE_UNDERWRITER:DEFAULT";
    private static final String SYSTEM_CUSTODY_PARTICIPANT_CODE = "SYSTEM_CUSTODY";
    private static final String SYSTEM_CUSTODY_SELF_TRADE_GROUP =
            "SYSTEM_CUSTODY:DEFAULT";
    private static final String ISSUANCE_LOCKUP_USER_KEY_PREFIX =
            "stock-issuance-lockup-";
    private static final String ISSUANCE_LOCKUP_ACCOUNT_CODE_PREFIX =
            "LOCKUP-";

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
    private final SimulationMarketSessionService marketSessionService;
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
            SimulationMarketSessionService marketSessionService,
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
        this.marketSessionService = marketSessionService;
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
        SimulationClockSnapshot clock = requirePausedPreOpen();
        LocalDate activeBusinessDate = marketLedgerFreezeGuard.acquireMutationPermit(
                "order-book instrument listing"
        );
        if (!activeBusinessDate.equals(clock.simulationDate())) {
            throw StockException.conflict(
                    "Simulation date and active market business date must match"
            );
        }
        validateInstrumentRequest(symbol, name, market, request);
        if (stockInstrumentRepository.existsById(symbol)) {
            throw StockException.conflict("Symbol already exists in virtual price market: " + symbol);
        }
        if (stockOrderBookInstrumentRepository.existsById(symbol)) {
            throw StockException.conflict("Order book symbol already exists: " + symbol);
        }

        InitialIssueAllocationPlan allocationPlan = resolveInitialIssueAllocation(
                request.initialIssueAllocation(),
                request.issuedShares(),
                request.listingAutoAccount()
        );
        BigDecimal tickSize = KoreanStockTickSizePolicy.tickSizeForCurrentPrice(market, request.initialPrice());
        BigDecimal priceLimitRate = request.priceLimitRate() == null ? BigDecimal.valueOf(30) : request.priceLimitRate();
        LocalDateTime now = clock.simulationDateTime();
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.save(
                StockOrderBookInstrument.listedWithTradableShares(
                        symbol,
                        name,
                        market,
                        request.initialPrice(),
                        request.issuedShares(),
                        allocationPlan.tradableShares(),
                        tickSize,
                        priceLimitRate,
                        now
                )
        );
        StockCorporateAction initialIssue = stockCorporateActionRepository.save(
                StockCorporateAction.initialIssue(symbol, request.issuedShares(), request.initialPrice(), now)
        );
        stockOrderBookMarketConfigRepository.save(
                allocationPlan.roleSeparated()
                        ? StockOrderBookMarketConfig.pendingActivation(symbol, now)
                        : StockOrderBookMarketConfig.enabled(symbol, now)
        );
        stockAutoMarketConfigRepository.save(StockAutoMarketConfig.defaults(symbol, now));
        stockPriceRepository.save(StockPrice.initial(symbol, request.initialPrice(), now));
        if (allocationPlan.roleSeparated()) {
            seedRoleSeparatedInitialAllocation(
                    symbol,
                    request.initialPrice(),
                    request.issuedShares(),
                    allocationPlan,
                    initialIssue.getId(),
                    now
            );
        } else {
            seedListingAutoAccount(
                    symbol,
                    name,
                    request.initialPrice(),
                    allocationPlan.tradableShares(),
                    request.listingAutoAccount(),
                    now
            );
        }
        return OrderBookInstrumentResponseMapper.toResponse(instrument, findPrice(instrument));
    }

    private SimulationClockSnapshot requirePausedPreOpen() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        if (clock.running()) {
            throw StockException.conflict(
                    "Pause the simulation clock before listing an order-book instrument"
            );
        }
        if (marketSessionService.currentSession() != SimulationMarketSession.PRE_OPEN) {
            throw StockException.conflict(
                    "Order-book instruments can only be listed during a paused pre-open"
            );
        }
        return clock;
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

    private InitialIssueAllocationPlan resolveInitialIssueAllocation(
            InitialIssueAllocationRequest request,
            long issuedShares,
            ListingAutoAccountRequest listingAutoAccount
    ) {
        String mode = request == null || request.mode() == null
                ? ROLE_SEPARATED_MODE
                : request.mode().trim().toUpperCase(Locale.ROOT);
        if (LEGACY_FULL_FLOAT_MODE.equals(mode)) {
            if (request != null && request.tradableShareRate() != null) {
                throw StockException.badRequest(
                        "Legacy full-float issuance must not specify a tradable-share rate"
                );
            }
            return new InitialIssueAllocationPlan(false, issuedShares, 0L);
        }
        if (!ROLE_SEPARATED_MODE.equals(mode)) {
            throw StockException.badRequest("Unsupported initial-issue allocation mode: " + mode);
        }
        if (listingAutoAccount != null) {
            throw StockException.badRequest(
                    "Role-separated issuance cannot create a legacy listing-auto account"
            );
        }
        if (issuedShares < 2L) {
            throw StockException.badRequest(
                    "Role-separated issuance requires at least two issued shares"
            );
        }
        BigDecimal tradableRate = request == null || request.tradableShareRate() == null
                ? DEFAULT_TRADABLE_SHARE_RATE
                : request.tradableShareRate();
        if (tradableRate.compareTo(MIN_TRADABLE_SHARE_RATE) < 0
                || tradableRate.compareTo(MAX_TRADABLE_SHARE_RATE) > 0) {
            throw StockException.badRequest(
                    "Role-separated tradable-share rate must be between 0.20 and 0.85"
            );
        }
        long tradableShares = BigDecimal.valueOf(issuedShares)
                .multiply(tradableRate)
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
        if (tradableShares <= 0L || tradableShares >= issuedShares) {
            throw StockException.badRequest(
                    "Role-separated issuance must leave both tradable and locked shares"
            );
        }
        return new InitialIssueAllocationPlan(
                true,
                tradableShares,
                Math.subtractExact(issuedShares, tradableShares)
        );
    }

    private void seedRoleSeparatedInitialAllocation(
            String symbol,
            BigDecimal issuePrice,
            long issuedShares,
            InitialIssueAllocationPlan allocationPlan,
            Long corporateActionId,
            LocalDateTime now
    ) {
        if (corporateActionId == null || corporateActionId <= 0L) {
            throw new IllegalStateException(
                    "Role-separated initial issue requires a persisted corporate action"
            );
        }
        MarketRoleParticipant underwriter = requireMarketRoleParticipant(
                DEFAULT_UNDERWRITER_PARTICIPANT_CODE,
                "ISSUE_UNDERWRITER",
                DEFAULT_UNDERWRITER_SELF_TRADE_GROUP
        );
        MarketRoleParticipant custody = requireMarketRoleParticipant(
                SYSTEM_CUSTODY_PARTICIPANT_CODE,
                "SYSTEM_CUSTODY",
                SYSTEM_CUSTODY_SELF_TRADE_GROUP
        );
        long lockupCustodyAccountId = createIssuanceLockupCustodyAccount(
                custody.participantId(),
                symbol,
                now
        );
        String underwriterUserKey = "stock-issue-underwriter-" + symbol.toLowerCase(Locale.ROOT);
        String underwriterAccountCode = "UW-" + symbol;
        int accountInserted = jdbcTemplate.update(
                """
                insert into stock_account(
                    user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (?, ?, 'ACTIVE', 'ISSUE_UNDERWRITER', ?, 0.00, ?, ?)
                """,
                underwriterUserKey,
                underwriterAccountCode,
                DEFAULT_UNDERWRITER_SELF_TRADE_GROUP,
                now,
                now
        );
        if (accountInserted != 1) {
            throw new IllegalStateException("Issue-underwriter account creation failed");
        }
        long underwriterAccountId = jdbcClient.sql(
                        "select id from stock_account where user_key = ?"
                )
                .param(underwriterUserKey)
                .query(Long.class)
                .single();
        int mappingInserted = jdbcTemplate.update(
                """
                insert into stock_market_participant_account(
                    participant_id, account_id, account_role, desk_code,
                    effective_from, effective_to, status, created_at, updated_at
                ) values (?, ?, 'ISSUE_UNDERWRITER', ?, ?, null, 'ACTIVE', ?, ?)
                """,
                underwriter.participantId(),
                underwriterAccountId,
                symbol,
                now.toLocalDate(),
                now,
                now
        );
        if (mappingInserted != 1) {
            throw new IllegalStateException("Issue-underwriter account mapping creation failed");
        }
        int contractInserted = jdbcTemplate.update(
                """
                insert into stock_underwriting_contract(
                    contract_code, corporate_action_id, symbol,
                    participant_id, account_id,
                    total_issue_quantity, tradable_allocation_quantity,
                    locked_allocation_quantity, external_allocation_quantity,
                    underwritten_quantity, issue_price, underwriting_type,
                    stabilization_start_date, stabilization_end_date,
                    stabilization_quantity_limit, stabilization_amount_limit,
                    status, policy_version, created_at, updated_at
                ) values (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, 0,
                    ?, ?, 'FIRM_COMMITMENT',
                    null, null, 0, 0.00,
                    'ALLOCATED', 1, ?, ?
                )
                """,
                "INITIAL-ISSUE:" + symbol,
                corporateActionId,
                symbol,
                underwriter.participantId(),
                underwriterAccountId,
                issuedShares,
                allocationPlan.tradableShares(),
                allocationPlan.lockedShares(),
                allocationPlan.tradableShares(),
                issuePrice,
                now,
                now
        );
        if (contractInserted != 1) {
            throw new IllegalStateException("Initial underwriting contract creation failed");
        }
        long contractId = jdbcClient.sql(
                        "select id from stock_underwriting_contract where contract_code = ?"
                )
                .param("INITIAL-ISSUE:" + symbol)
                .query(Long.class)
                .single();
        insertInitialUnderwritingPolicy(contractId, symbol, now);
        insertInitialHolding(
                underwriterAccountId,
                symbol,
                allocationPlan.tradableShares(),
                issuePrice,
                now
        );
        insertInitialHolding(
                lockupCustodyAccountId,
                symbol,
                allocationPlan.lockedShares(),
                issuePrice,
                now
        );
        insertSecurityAllocation(
                "INITIAL_ISSUE:" + symbol + ":UNDERWRITER",
                corporateActionId,
                contractId,
                underwriterAccountId,
                symbol,
                allocationPlan.tradableShares(),
                issuePrice,
                "INITIAL_FLOAT_UNDERWRITER",
                "TRADABLE",
                now
        );
        insertSecurityAllocation(
                "INITIAL_ISSUE:" + symbol + ":LOCKED",
                corporateActionId,
                contractId,
                lockupCustodyAccountId,
                symbol,
                allocationPlan.lockedShares(),
                issuePrice,
                "INITIAL_LOCKED_CUSTODY",
                "LOCKED",
                now
        );
        Long allocatedQuantity = jdbcClient.sql(
                        """
                        select coalesce(sum(quantity), 0)
                          from stock_holding
                         where symbol = ?
                        """
                )
                .param(symbol)
                .query(Long.class)
                .single();
        if (allocatedQuantity == null || allocatedQuantity != issuedShares) {
            throw new IllegalStateException(
                    "Initial security allocation does not reconcile to issued shares"
            );
        }
    }

    private void insertInitialUnderwritingPolicy(
            long contractId,
            String symbol,
            LocalDateTime now
    ) {
        String contractCode = "INITIAL-ISSUE:" + symbol;
        String configJson = """
                {
                  "preset": "SCALED_PASSIVE_UNDERWRITER_SUPPLY_V1",
                  "contractId": %d,
                  "contractCode": "%s",
                  "symbol": "%s",
                  "status": "ALLOCATED",
                  "quantityLimit": 0,
                  "amountLimit": 0.00,
                  "sellOnly": true,
                  "passiveOnly": true,
                  "cancellationRefundsSubmissionBudget": false
                }
                """.formatted(contractId, contractCode, symbol).trim();
        int inserted = jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no,
                    effective_business_date, status, config_json,
                    change_reason, changed_by, created_at, updated_at
                ) values (
                    'UNDERWRITING_CONTRACT', ?, 1,
                    ?, 'ACTIVE', ?,
                    'Create inactive role-separated underwriting contract',
                    'SYSTEM_INITIAL_ISSUE', ?, ?
                )
                """,
                contractCode,
                now.toLocalDate(),
                configJson,
                now,
                now
        );
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Initial underwriting policy creation failed: " + symbol
            );
        }
    }

    private MarketRoleParticipant requireMarketRoleParticipant(
            String participantCode,
            String participantType,
            String selfTradeGroupId
    ) {
        return jdbcClient.sql(
                        """
                        select id, participant_type, status, self_trade_group_id
                          from stock_market_participant
                         where participant_code = ?
                        """
                )
                .param(participantCode)
                .query((rs, rowNum) -> new MarketRoleParticipant(
                        rs.getLong("id"),
                        rs.getString("participant_type"),
                        rs.getString("status"),
                        rs.getString("self_trade_group_id")
                ))
                .optional()
                .filter(participant -> participantType.equals(participant.participantType()))
                .filter(participant -> "ACTIVE".equals(participant.status()))
                .filter(participant -> selfTradeGroupId.equals(participant.selfTradeGroupId()))
                .orElseThrow(() -> StockException.conflict(
                        "Required market-role participant is not active or consistent: "
                                + participantCode
                ));
    }

    private long createIssuanceLockupCustodyAccount(
            long participantId,
            String symbol,
            LocalDateTime now
    ) {
        String userKey = ISSUANCE_LOCKUP_USER_KEY_PREFIX
                + symbol.toLowerCase(Locale.ROOT);
        String accountCode = ISSUANCE_LOCKUP_ACCOUNT_CODE_PREFIX + symbol;
        int accountInserted = jdbcTemplate.update(
                """
                insert into stock_account(
                    user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (?, ?, 'ACTIVE', 'SYSTEM_CUSTODY', ?, 0.00, ?, ?)
                """,
                userKey,
                accountCode,
                SYSTEM_CUSTODY_SELF_TRADE_GROUP,
                now,
                now
        );
        if (accountInserted != 1) {
            throw new IllegalStateException(
                    "Issuance-lockup custody account creation failed: " + symbol
            );
        }
        long accountId = jdbcClient.sql(
                        "select id from stock_account where user_key = ?"
                )
                .param(userKey)
                .query(Long.class)
                .single();
        int mappingInserted = jdbcTemplate.update(
                        """
                        insert into stock_market_participant_account(
                            participant_id, account_id, account_role, desk_code,
                            effective_from, effective_to, status, created_at, updated_at
                        ) values (
                            ?, ?, 'SYSTEM_CUSTODY', ?,
                            ?, null, 'ACTIVE', ?, ?
                        )
                        """,
                participantId,
                accountId,
                "ISSUANCE_LOCKUP:" + symbol,
                now.toLocalDate(),
                now,
                now
        );
        if (mappingInserted != 1) {
            throw new IllegalStateException(
                    "Issuance-lockup custody mapping creation failed: " + symbol
            );
        }
        return accountId;
    }

    private void insertInitialHolding(
            long accountId,
            String symbol,
            long quantity,
            BigDecimal averagePrice,
            LocalDateTime now
    ) {
        int inserted = jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity, average_price, updated_at
                ) values (?, ?, ?, 0, ?, ?)
                """,
                accountId,
                symbol,
                quantity,
                averagePrice,
                now
        );
        if (inserted != 1) {
            throw new IllegalStateException("Initial security holding allocation failed");
        }
    }

    private void insertSecurityAllocation(
            String idempotencyKey,
            long corporateActionId,
            long contractId,
            long destinationAccountId,
            String symbol,
            long quantity,
            BigDecimal unitPrice,
            String allocationReason,
            String tradabilityStatus,
            LocalDateTime now
    ) {
        int inserted = jdbcTemplate.update(
                """
                insert into stock_security_allocation_ledger(
                    idempotency_key, event_type, corporate_action_id,
                    underwriting_contract_id, source_account_id,
                    destination_account_id, symbol, quantity, unit_price,
                    allocation_reason, tradability_status,
                    effective_business_date, unlock_business_date, created_at
                ) values (
                    ?, 'INITIAL_ISSUE', ?, ?,
                    null, ?, ?, ?, ?,
                    ?, ?, ?, null, ?
                )
                """,
                idempotencyKey,
                corporateActionId,
                contractId,
                destinationAccountId,
                symbol,
                quantity,
                unitPrice,
                allocationReason,
                tradabilityStatus,
                now.toLocalDate(),
                now
        );
        if (inserted != 1) {
            throw new IllegalStateException("Initial security-allocation audit insert failed");
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
                    user_key, status, participant_category, cash_balance, created_at, updated_at
                )
                values (?, 'ACTIVE', 'LISTING_UNDERWRITER', 0.00, ?, ?)
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

    private record InitialIssueAllocationPlan(
            boolean roleSeparated,
            long tradableShares,
            long lockedShares
    ) {
    }

    private record MarketRoleParticipant(
            long participantId,
            String participantType,
            String status,
            String selfTradeGroupId
    ) {
    }

}
