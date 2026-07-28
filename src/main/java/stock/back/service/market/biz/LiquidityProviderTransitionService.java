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
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.LiquidityProviderProvisionRequest;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
public class LiquidityProviderTransitionService {

    private static final String PARTICIPANT_CODE = "DEFAULT_LIQUIDITY_PROVIDER";
    private static final String PARTICIPANT_TYPE = "LIQUIDITY_PROVIDER";
    private static final String SELF_TRADE_GROUP_ID = "LIQUIDITY_PROVIDER:DEFAULT";
    private static final String LIVE_ACTIVE = "LIVE_ACTIVE";
    private static final String PENDING_ACTIVATION = "PENDING_ACTIVATION";

    private static final BigDecimal DEFAULT_SEED_INVENTORY_RATE = new BigDecimal("0.005000");
    private static final BigDecimal MIN_SEED_INVENTORY_RATE = new BigDecimal("0.001000");
    private static final BigDecimal MAX_SEED_INVENTORY_RATE = new BigDecimal("0.020000");
    private static final BigDecimal DEFAULT_CASH_MULTIPLIER = BigDecimal.ONE;
    private static final BigDecimal MIN_CASH_MULTIPLIER = new BigDecimal("0.500000");
    private static final BigDecimal MAX_CASH_MULTIPLIER = new BigDecimal("2.000000");

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;
    private final MarketRoleActivationDateService activationDateService;
    private final LiquidityProviderPolicyPresetCatalog policyPresetCatalog;
    private final MarketReferenceVolumeResolver referenceVolumeResolver;

    public LiquidityProviderTransitionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SimulationClockService simulationClockService,
            MarketLedgerFreezeGuard marketLedgerFreezeGuard,
            MarketRoleActivationDateService activationDateService,
            LiquidityProviderPolicyPresetCatalog policyPresetCatalog,
            MarketReferenceVolumeResolver referenceVolumeResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.simulationClockService = simulationClockService;
        this.marketLedgerFreezeGuard = marketLedgerFreezeGuard;
        this.activationDateService = activationDateService;
        this.policyPresetCatalog = policyPresetCatalog;
        this.referenceVolumeResolver = referenceVolumeResolver;
    }

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public void provisionLive(
            String symbol,
            LiquidityProviderProvisionRequest request,
            String requestedBy
    ) {
        String normalizedSymbol = normalizedSymbol(symbol);
        ExistingTransition existing = lockTransition(normalizedSymbol);
        if (existing != null) {
            if (LIVE_ACTIVE.equals(existing.stage())
                    || PENDING_ACTIVATION.equals(existing.stage())) {
                return;
            }
            throw StockException.conflict(
                    "Liquidity transition is not provisionable in stage " + existing.stage()
            );
        }
        LocalDate activeBusinessDate = marketLedgerFreezeGuard.acquireJdbcMutationPermit(
                "liquidity-provider live provisioning"
        );
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDate activationDate = activationDateService.resolveNextOpeningDate(
                clock,
                activeBusinessDate
        );

        MarketSymbol marketSymbol = lockMarketSymbol(normalizedSymbol);
        ExistingTransition concurrentlyProvisioned = lockTransition(normalizedSymbol);
        if (concurrentlyProvisioned != null) {
            if (LIVE_ACTIVE.equals(concurrentlyProvisioned.stage())
                    || PENDING_ACTIVATION.equals(concurrentlyProvisioned.stage())) {
                return;
            }
            throw StockException.conflict(
                    "Liquidity transition is not provisionable in stage "
                            + concurrentlyProvisioned.stage()
            );
        }
        if (mandateExists(normalizedSymbol)) {
            throw StockException.conflict(
                    "A liquidity-provider mandate already exists for " + normalizedSymbol
            );
        }
        long sourceAccountId = resolveSourceAccountId(
                normalizedSymbol,
                request == null ? null : request.sourceAccountId()
        );
        SourceHolding source = lockSourceHolding(sourceAccountId, normalizedSymbol);
        verifyIssuedShareReconciliation(normalizedSymbol, marketSymbol.issuedShares());

        BigDecimal requestedReferenceVolumeRate =
                request == null ? null : request.referenceDailyVolumeRate();
        MarketReferenceVolumeResolver.Resolution referenceVolumeResolution =
                requestedReferenceVolumeRate == null
                        ? referenceVolumeResolver.resolve(List.of(
                                new MarketReferenceVolumeResolver.SymbolFloat(
                                        normalizedSymbol,
                                        marketSymbol.tradableShares()
                                )
                        )).get(normalizedSymbol)
                        : null;
        BigDecimal referenceVolumeRate = requestedReferenceVolumeRate == null
                ? referenceVolumeResolution.referenceDailyVolumeRate()
                : normalizedRate(
                        requestedReferenceVolumeRate,
                        MarketReferenceVolumeResolver.FALLBACK_FLOAT_RATE,
                        MarketReferenceVolumeResolver.MIN_FLOAT_RATE,
                        MarketReferenceVolumeResolver.MAX_FLOAT_RATE,
                        "Reference daily-volume rate"
                );
        BigDecimal seedInventoryRate = normalizedRate(
                request == null ? null : request.seedInventoryRate(),
                DEFAULT_SEED_INVENTORY_RATE,
                MIN_SEED_INVENTORY_RATE,
                MAX_SEED_INVENTORY_RATE,
                "Seed inventory rate"
        );
        BigDecimal cashMultiplier = normalizedRate(
                request == null ? null : request.initialCashToInventoryValue(),
                DEFAULT_CASH_MULTIPLIER,
                MIN_CASH_MULTIPLIER,
                MAX_CASH_MULTIPLIER,
                "Initial cash multiplier"
        );
        long referenceDailyVolume = referenceVolumeResolution == null
                ? scaledQuantity(marketSymbol.tradableShares(), referenceVolumeRate)
                : referenceVolumeResolution.referenceDailyVolume();
        long seedInventoryQuantity = scaledQuantity(
                marketSymbol.tradableShares(),
                seedInventoryRate
        );
        if (source.availableQuantity() < seedInventoryQuantity) {
            throw StockException.conflict(
                    "The source account does not have enough unreserved inventory for the LP seed: "
                            + "available=" + source.availableQuantity()
                            + ", required=" + seedInventoryQuantity
            );
        }
        BigDecimal seedUnitPrice = source.averagePrice().signum() > 0
                ? source.averagePrice()
                : marketSymbol.currentPrice();
        BigDecimal seedCashAmount = marketSymbol.currentPrice()
                .multiply(BigDecimal.valueOf(seedInventoryQuantity))
                .multiply(cashMultiplier)
                .setScale(2, RoundingMode.HALF_UP);
        if (seedCashAmount.signum() <= 0) {
            throw StockException.badRequest("Liquidity-provider seed cash must be positive");
        }

        MarketParticipant participant = requireDefaultParticipant();
        LocalDateTime now = clock.simulationDateTime();
        long liquidityAccountId = insertLiquidityAccount(
                normalizedSymbol,
                seedCashAmount,
                now
        );
        insertParticipantAccount(
                participant.id(),
                liquidityAccountId,
                normalizedSymbol,
                activationDate,
                now
        );
        transferSeedInventory(
                source,
                liquidityAccountId,
                normalizedSymbol,
                seedInventoryQuantity,
                seedUnitPrice,
                now
        );
        insertOpeningGrant(
                liquidityAccountId,
                seedCashAmount,
                activationDate,
                normalizeRequestedBy(requestedBy),
                now
        );
        long mandateId = insertLiveMandate(
                participant.id(),
                liquidityAccountId,
                normalizedSymbol,
                marketSymbol.tradableShares(),
                referenceDailyVolume,
                seedInventoryQuantity,
                seedCashAmount,
                marketSymbol.currentPrice(),
                activationDate,
                now
        );
        String changeReason = normalizeReason(
                request == null ? null : request.changeReason(),
                "Create a dedicated live liquidity provider"
        );
        insertTransition(
                normalizedSymbol,
                mandateId,
                participant.id(),
                liquidityAccountId,
                source.accountId(),
                referenceDailyVolume,
                seedInventoryQuantity,
                seedCashAmount,
                activationDate,
                normalizeRequestedBy(requestedBy),
                changeReason,
                now
        );
        ExistingTransition provisioned = lockTransition(normalizedSymbol);
        if (provisioned == null) {
            throw new IllegalStateException(
                    "Liquidity transition is missing after live provisioning"
            );
        }
        requireLiquidityAccountEligible(provisioned, activationDate);
        insertAllocationAudit(
                normalizedSymbol,
                source.accountId(),
                liquidityAccountId,
                seedInventoryQuantity,
                seedUnitPrice,
                activationDate,
                now
        );
        insertPolicyVersion(
                normalizedSymbol,
                1L,
                "LIVE",
                referenceVolumeRate,
                seedInventoryRate,
                cashMultiplier,
                referenceDailyVolume,
                seedInventoryQuantity,
                seedCashAmount,
                activationDate,
                changeReason,
                normalizeRequestedBy(requestedBy),
                now
        );
        verifyIssuedShareReconciliation(normalizedSymbol, marketSymbol.issuedShares());
    }

    private ExistingTransition lockTransition(String symbol) {
        return jdbcClient.sql(
                        """
                        select id, symbol, mandate_id, participant_id,
                               liquidity_account_id, source_account_id,
                               stage, reference_daily_volume,
                               seed_inventory_quantity, seed_cash_amount,
                               effective_business_date, policy_version
                          from stock_liquidity_transition
                         where symbol = ?
                         for update
                        """
                )
                .param(symbol)
                .query((rs, rowNum) -> new ExistingTransition(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getLong("mandate_id"),
                        rs.getLong("participant_id"),
                        rs.getLong("liquidity_account_id"),
                        rs.getLong("source_account_id"),
                        rs.getString("stage"),
                        rs.getLong("reference_daily_volume"),
                        rs.getLong("seed_inventory_quantity"),
                        rs.getBigDecimal("seed_cash_amount"),
                        rs.getObject("effective_business_date", LocalDate.class),
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElse(null);
    }

    private MarketSymbol lockMarketSymbol(String symbol) {
        MarketSymbol marketSymbol = jdbcClient.sql(
                        """
                        select instrument.symbol, instrument.issued_shares,
                               instrument.tradable_shares, price.current_price,
                               market.enabled as market_enabled,
                               market.market_status
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                          join stock_price price
                            on price.symbol = instrument.symbol
                           and price.current_price > 0
                         where instrument.symbol = ?
                           and instrument.enabled = true
                           and instrument.issued_shares > 0
                           and instrument.tradable_shares > 0
                         for update
                        """
                )
                .param(symbol)
                .query((rs, rowNum) -> new MarketSymbol(
                        rs.getString("symbol"),
                        rs.getLong("issued_shares"),
                        rs.getLong("tradable_shares"),
                        rs.getBigDecimal("current_price"),
                        rs.getBoolean("market_enabled"),
                        rs.getString("market_status")
                ))
                .optional()
                .orElseThrow(() -> StockException.notFound(
                        "Order-book symbol not found: " + symbol
                ));
        if (marketSymbol.configuredMarket()) {
            return marketSymbol;
        }
        if (marketSymbol.pendingRoleSeparatedMarket()
                && hasEligibleLiquiditySource(symbol)) {
            return marketSymbol;
        }
        throw StockException.conflict(
                "Symbol must be either an active market or a pending role-separated listing: "
                        + symbol
        );
    }

    private boolean hasEligibleLiquiditySource(String symbol) {
        Boolean exists = jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_holding holding
                              join stock_account account
                                on account.id = holding.account_id
                               and account.status = 'ACTIVE'
                             where holding.symbol = ?
                               and holding.quantity > holding.reserved_quantity
                               and account.participant_category in (
                                   'ISSUE_UNDERWRITER', 'SYSTEM_CUSTODY'
                               )
                        )
                        """
                )
                .param(symbol)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    private boolean mandateExists(String symbol) {
        Boolean exists = jdbcClient.sql(
                        "select exists(select 1 from stock_liquidity_mandate where symbol = ?)"
                )
                .param(symbol)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    private long resolveSourceAccountId(
            String symbol,
            Long requestedSourceAccountId
    ) {
        if (requestedSourceAccountId != null) {
            if (requestedSourceAccountId <= 0L) {
                throw StockException.badRequest("Source account id must be positive");
            }
            return requestedSourceAccountId;
        }
        List<Long> sourceAccounts = jdbcClient.sql(
                        """
                        select distinct candidate.account_id
                          from (
                              select contract.account_id
                                from stock_underwriting_contract contract
                               where contract.symbol = ?
                                 and contract.status in (
                                     'ALLOCATED', 'STABILIZING', 'COMPLETED'
                                 )
                              union all
                              select allocation.destination_account_id
                                from stock_security_allocation_ledger allocation
                               where allocation.symbol = ?
                                 and allocation.allocation_reason = 'INITIAL_FLOAT_CUSTODY'
                                 and allocation.tradability_status = 'TRADABLE'
                          ) candidate
                          join stock_account account
                            on account.id = candidate.account_id
                           and account.status = 'ACTIVE'
                           and account.participant_category in (
                               'ISSUE_UNDERWRITER', 'SYSTEM_CUSTODY'
                           )
                          join stock_holding holding
                            on holding.account_id = candidate.account_id
                           and holding.symbol = ?
                           and holding.quantity > holding.reserved_quantity
                         order by candidate.account_id
                        """
                )
                .param(symbol)
                .param(symbol)
                .param(symbol)
                .query(Long.class)
                .list();
        if (sourceAccounts.size() != 1) {
            throw StockException.conflict(
                    "Specify a source account because exactly one eligible issue or float-custody account "
                            + "could not be resolved for " + symbol
            );
        }
        return sourceAccounts.getFirst();
    }

    private SourceHolding lockSourceHolding(long accountId, String symbol) {
        return jdbcClient.sql(
                        """
                        select account.id as account_id,
                               account.status as account_status,
                               account.participant_category,
                               holding.quantity,
                               holding.reserved_quantity,
                               holding.average_price
                          from stock_account account
                          join stock_holding holding
                            on holding.account_id = account.id
                           and holding.symbol = ?
                         where account.id = ?
                         for update
                        """
                )
                .param(symbol)
                .param(accountId)
                .query((rs, rowNum) -> new SourceHolding(
                        rs.getLong("account_id"),
                        rs.getString("account_status"),
                        rs.getString("participant_category"),
                        rs.getLong("quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getBigDecimal("average_price")
                ))
                .optional()
                .filter(source -> "ACTIVE".equals(source.accountStatus()))
                .filter(source -> "ISSUE_UNDERWRITER".equals(source.participantCategory())
                        || "SYSTEM_CUSTODY".equals(source.participantCategory()))
                .orElseThrow(() -> StockException.conflict(
                        "LP seed source must be an active issue-underwriter or float-custody account "
                                + "holding " + symbol
                ));
    }

    private MarketParticipant requireDefaultParticipant() {
        return jdbcClient.sql(
                        """
                        select id, participant_type, status, self_trade_group_id
                          from stock_market_participant
                         where participant_code = ?
                         for update
                        """
                )
                .param(PARTICIPANT_CODE)
                .query((rs, rowNum) -> new MarketParticipant(
                        rs.getLong("id"),
                        rs.getString("participant_type"),
                        rs.getString("status"),
                        rs.getString("self_trade_group_id")
                ))
                .optional()
                .filter(participant -> PARTICIPANT_TYPE.equals(participant.participantType()))
                .filter(participant -> "ACTIVE".equals(participant.status()))
                .filter(participant -> SELF_TRADE_GROUP_ID.equals(participant.selfTradeGroupId()))
                .orElseThrow(() -> StockException.conflict(
                        "Default liquidity-provider participant is missing or inconsistent"
                ));
    }

    private long insertLiquidityAccount(
            String symbol,
            BigDecimal seedCashAmount,
            LocalDateTime now
    ) {
        String userKey = "stock-liquidity-provider-" + symbol.toLowerCase(Locale.ROOT);
        String accountCode = "LP-" + symbol;
        return insertWithGeneratedKey(
                """
                insert into stock_account(
                    user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (?, ?, 'ACTIVE', 'LIQUIDITY_PROVIDER', ?, ?, ?, ?)
                """,
                statement -> {
                    statement.setString(1, userKey);
                    statement.setString(2, accountCode);
                    statement.setString(3, SELF_TRADE_GROUP_ID);
                    statement.setBigDecimal(4, seedCashAmount);
                    statement.setObject(5, now);
                    statement.setObject(6, now);
                }
        );
    }

    private void insertParticipantAccount(
            long participantId,
            long accountId,
            String symbol,
            LocalDate businessDate,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_market_participant_account(
                            participant_id, account_id, account_role, desk_code,
                            effective_from, effective_to, status, created_at, updated_at
                        ) values (?, ?, 'LIQUIDITY_PROVIDER', ?, ?, null, 'ACTIVE', ?, ?)
                        """,
                        participantId,
                        accountId,
                        symbol,
                        businessDate,
                        now,
                        now
                ),
                "Liquidity-provider account-role mapping"
        );
    }

    private void transferSeedInventory(
            SourceHolding source,
            long destinationAccountId,
            String symbol,
            long quantity,
            BigDecimal unitPrice,
            LocalDateTime now
    ) {
        int sourceUpdated = jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = quantity - ?,
                       updated_at = ?
                 where account_id = ?
                   and symbol = ?
                   and quantity - reserved_quantity >= ?
                """,
                quantity,
                now,
                source.accountId(),
                symbol,
                quantity
        );
        requireSingleUpdate(sourceUpdated, "Liquidity-provider source inventory transfer");
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_holding(
                            account_id, symbol, quantity, reserved_quantity,
                            average_price, updated_at
                        ) values (?, ?, ?, 0, ?, ?)
                        """,
                        destinationAccountId,
                        symbol,
                        quantity,
                        unitPrice,
                        now
                ),
                "Liquidity-provider destination inventory transfer"
        );
    }

    private void insertOpeningGrant(
            long accountId,
            BigDecimal amount,
            LocalDate businessDate,
            String requestedBy,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_account_cash_flow(
                            account_id, flow_type, amount, reason, created_by,
                            corporate_action_id, corporate_action_entitlement_id,
                            effective_business_date, created_at
                        ) values (?, 'DEPOSIT', ?, 'OPENING_GRANT', ?,
                                  null, null, ?, ?)
                        """,
                        accountId,
                        amount,
                        requestedBy,
                        businessDate,
                        now
                ),
                "Liquidity-provider opening grant audit"
        );
    }

    private long insertLiveMandate(
            long participantId,
            long accountId,
            String symbol,
            long tradableShares,
            long referenceDailyVolume,
            long seedInventoryQuantity,
            BigDecimal seedCashAmount,
            BigDecimal currentPrice,
            LocalDate businessDate,
            LocalDateTime now
    ) {
        BigDecimal openingNetAssetValue = seedCashAmount.add(
                currentPrice.multiply(BigDecimal.valueOf(seedInventoryQuantity))
        );
        LiquidityProviderPolicyPresetCatalog.ResolvedPolicy policy =
                policyPresetCatalog.resolveBalancedForReferenceVolume(
                        tradableShares,
                        referenceDailyVolume,
                        seedInventoryQuantity,
                        openingNetAssetValue
                ).policy();
        return insertWithGeneratedKey(
                """
                insert into stock_liquidity_mandate(
                    participant_id, account_id, symbol, mandate_code,
                    execution_mode, status, contract_start_date, contract_end_date,
                    target_spread_ticks, max_spread_ticks, max_order_quantity,
                    reference_daily_volume, target_open_participation_rate,
                    max_open_participation_rate,
                    max_single_order_participation_rate, external_depth_levels,
                    max_external_depth_participation_rate,
                    daily_execution_participation_rate, daily_submission_multiplier,
                    target_inventory_quantity, inventory_band_quantity,
                    inventory_skew_ticks, primary_regime_weight,
                    liquidity_size_sensitivity, volatility_spread_max_ticks,
                    price_regime_max_skew_ticks, passive_only,
                    minimum_quote_lifetime_seconds, reprice_threshold_ticks,
                    order_ttl_seconds, quote_interval_seconds,
                    daily_loss_limit_amount, next_quote_at, policy_version,
                    created_at, updated_at
                ) values (
                    ?, ?, ?, ?,
                    'LIVE', 'PENDING', ?, null,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, true,
                    ?, ?, ?, ?,
                    ?, null, 1, ?, ?
                )
                """,
                statement -> {
                    statement.setLong(1, participantId);
                    statement.setLong(2, accountId);
                    statement.setString(3, symbol);
                    statement.setString(4, "LP-SCALED:" + symbol);
                    statement.setObject(5, businessDate);
                    statement.setInt(6, policy.targetSpreadTicks());
                    statement.setInt(7, policy.maxSpreadTicks());
                    statement.setLong(8, policy.maxOrderQuantity());
                    statement.setLong(9, policy.referenceDailyVolume());
                    statement.setBigDecimal(10, policy.targetOpenParticipationRate());
                    statement.setBigDecimal(11, policy.maxOpenParticipationRate());
                    statement.setBigDecimal(12, policy.maxSingleOrderParticipationRate());
                    statement.setInt(13, policy.externalDepthLevels());
                    statement.setBigDecimal(14, policy.maxExternalDepthParticipationRate());
                    statement.setBigDecimal(15, policy.dailyExecutionParticipationRate());
                    statement.setBigDecimal(16, policy.dailySubmissionMultiplier());
                    statement.setLong(17, policy.targetInventoryQuantity());
                    statement.setLong(18, policy.inventoryBandQuantity());
                    statement.setInt(19, policy.inventorySkewTicks());
                    statement.setBigDecimal(20, policy.primaryRegimeWeight());
                    statement.setBigDecimal(21, policy.liquiditySizeSensitivity());
                    statement.setInt(22, policy.volatilitySpreadMaxTicks());
                    statement.setInt(23, policy.priceRegimeMaxSkewTicks());
                    statement.setInt(24, policy.minimumQuoteLifetimeSeconds());
                    statement.setInt(25, policy.repriceThresholdTicks());
                    statement.setInt(26, policy.orderTtlSeconds());
                    statement.setInt(27, policy.quoteIntervalSeconds());
                    statement.setBigDecimal(28, policy.dailyLossLimitAmount());
                    statement.setObject(29, now);
                    statement.setObject(30, now);
                }
        );
    }

    private void insertTransition(
            String symbol,
            long mandateId,
            long participantId,
            long liquidityAccountId,
            long sourceAccountId,
            long referenceDailyVolume,
            long seedInventoryQuantity,
            BigDecimal seedCashAmount,
            LocalDate businessDate,
            String requestedBy,
            String changeReason,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_liquidity_transition(
                            transition_key, symbol, mandate_id, participant_id,
                            liquidity_account_id, source_account_id, legacy_account_id,
                            stage, reference_daily_volume, seed_inventory_quantity,
                            seed_cash_amount, effective_business_date,
                            legacy_disabled_at, activated_at,
                            requested_by, change_reason, policy_version,
                            created_at, updated_at
                        ) values (
                            ?, ?, ?, ?, ?, ?, null,
                            'PENDING_ACTIVATION', ?, ?, ?, ?,
                            null, null, ?, ?, 1, ?, ?
                        )
                        """,
                        "LP-TRANSITION:" + symbol,
                        symbol,
                        mandateId,
                        participantId,
                        liquidityAccountId,
                        sourceAccountId,
                        referenceDailyVolume,
                        seedInventoryQuantity,
                        seedCashAmount,
                        businessDate,
                        requestedBy,
                        changeReason,
                        now,
                        now
                ),
                "Liquidity-provider transition audit"
        );
    }

    private void insertAllocationAudit(
            String symbol,
            long sourceAccountId,
            long destinationAccountId,
            long quantity,
            BigDecimal unitPrice,
            LocalDate businessDate,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_security_allocation_ledger(
                            idempotency_key, event_type, corporate_action_id,
                            underwriting_contract_id, source_account_id,
                            destination_account_id, symbol, quantity, unit_price,
                            allocation_reason, tradability_status,
                            effective_business_date, unlock_business_date, created_at
                        ) values (
                            ?, 'MANUAL_REALLOCATION', null, null, ?,
                            ?, ?, ?, ?,
                            'LIQUIDITY_SEED_TRANSFER', 'TRADABLE',
                            ?, null, ?
                        )
                        """,
                        "LP-SEED:" + symbol,
                        sourceAccountId,
                        destinationAccountId,
                        symbol,
                        quantity,
                        unitPrice,
                        businessDate,
                        now
                ),
                "Liquidity-provider security allocation audit"
        );
    }

    private void insertPolicyVersion(
            String symbol,
            long version,
            String executionMode,
            BigDecimal referenceVolumeRate,
            BigDecimal seedInventoryRate,
            BigDecimal cashMultiplier,
            long referenceDailyVolume,
            long seedInventoryQuantity,
            BigDecimal seedCashAmount,
            LocalDate businessDate,
            String changeReason,
            String requestedBy,
            LocalDateTime now
    ) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("preset", "SCALED_BALANCED_V1");
        config.put("symbol", symbol);
        config.put("executionMode", executionMode);
        config.put("activationAction", "PROVISION");
        config.put("targetStatus", "ACTIVE");
        config.put("referenceDailyVolumeRate", referenceVolumeRate);
        config.put("seedInventoryRate", seedInventoryRate);
        config.put("initialCashToInventoryValue", cashMultiplier);
        config.put("referenceDailyVolume", referenceDailyVolume);
        config.put("seedInventoryQuantity", seedInventoryQuantity);
        config.put("seedCashAmount", seedCashAmount);
        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Liquidity-provider policy JSON serialization failed",
                    ex
            );
        }
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_market_policy_version(
                            policy_scope, scope_key, version_no,
                            effective_business_date, status, config_json,
                            change_reason, changed_by, created_at, updated_at
                        ) values (
                            'LIQUIDITY_MANDATE', ?, ?, ?, 'SCHEDULED', ?,
                            ?, ?, ?, ?
                        )
                        """,
                        symbol,
                        version,
                        businessDate,
                        configJson,
                        changeReason,
                        requestedBy,
                        now,
                        now
                ),
                "Liquidity-provider policy version"
        );
    }

    private void requireLiquidityAccountEligible(
            ExistingTransition transition,
            LocalDate businessDate
    ) {
        Boolean eligible = jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_liquidity_mandate mandate
                              join stock_market_participant participant
                                on participant.id = mandate.participant_id
                              join stock_account account
                                on account.id = mandate.account_id
                              join stock_market_participant_account mapping
                                on mapping.participant_id = participant.id
                               and mapping.account_id = account.id
                              join stock_holding holding
                                on holding.account_id = account.id
                               and holding.symbol = mandate.symbol
                             where mandate.id = ?
                               and mandate.symbol = ?
                               and mandate.execution_mode = 'LIVE'
                               and mandate.status = 'PENDING'
                               and mandate.reference_daily_volume = ?
                               and mandate.target_inventory_quantity = ?
                               and participant.id = ?
                               and participant.participant_type = 'LIQUIDITY_PROVIDER'
                               and participant.status = 'ACTIVE'
                               and participant.self_trade_group_id = ?
                               and account.id = ?
                               and account.participant_category = 'LIQUIDITY_PROVIDER'
                               and account.status = 'ACTIVE'
                               and account.self_trade_group_id = ?
                               and account.cash_balance >= 0
                               and mapping.account_role = 'LIQUIDITY_PROVIDER'
                               and mapping.desk_code = mandate.symbol
                               and mapping.status = 'ACTIVE'
                               and mapping.effective_from <= ?
                               and (mapping.effective_to is null or mapping.effective_to >= ?)
                               and holding.quantity = ?
                               and holding.reserved_quantity = 0
                               and not exists (
                                   select 1
                                     from stock_holding unmanaged_holding
                                    where unmanaged_holding.account_id = account.id
                                      and unmanaged_holding.symbol <> mandate.symbol
                                      and (
                                          unmanaged_holding.quantity > 0
                                          or unmanaged_holding.reserved_quantity > 0
                                      )
                               )
                        )
                        """
                )
                .param(transition.mandateId())
                .param(transition.symbol())
                .param(transition.referenceDailyVolume())
                .param(transition.seedInventoryQuantity())
                .param(transition.participantId())
                .param(SELF_TRADE_GROUP_ID)
                .param(transition.liquidityAccountId())
                .param(SELF_TRADE_GROUP_ID)
                .param(businessDate)
                .param(businessDate)
                .param(transition.seedInventoryQuantity())
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(eligible)) {
            throw StockException.conflict(
                    "Liquidity-provider account, role mapping, mandate, or self-trade group is inconsistent"
            );
        }
    }

    private void verifyIssuedShareReconciliation(String symbol, long issuedShares) {
        Long holdingQuantity = jdbcClient.sql(
                        """
                        select coalesce(sum(quantity), 0)
                          from stock_holding
                         where symbol = ?
                        """
                )
                .param(symbol)
                .query(Long.class)
                .single();
        if (holdingQuantity == null || holdingQuantity != issuedShares) {
            throw StockException.conflict(
                    "Stock holdings do not reconcile to issued shares for " + symbol
                            + ": holdings=" + holdingQuantity
                            + ", issued=" + issuedShares
            );
        }
    }

    private BigDecimal normalizedRate(
            BigDecimal value,
            BigDecimal defaultValue,
            BigDecimal minimum,
            BigDecimal maximum,
            String label
    ) {
        BigDecimal normalized = value == null ? defaultValue : value;
        if (normalized.compareTo(minimum) < 0 || normalized.compareTo(maximum) > 0) {
            throw StockException.badRequest(
                    label + " must be between "
                            + minimum.toPlainString() + " and " + maximum.toPlainString()
            );
        }
        return normalized.setScale(6, RoundingMode.HALF_UP);
    }

    private long scaledQuantity(long baseQuantity, BigDecimal rate) {
        return Math.max(
                1L,
                BigDecimal.valueOf(baseQuantity)
                        .multiply(rate)
                        .setScale(0, RoundingMode.DOWN)
                        .longValueExact()
        );
    }

    private String normalizedSymbol(String symbol) {
        String normalized = MarketTextNormalizer.symbol(symbol);
        if (normalized.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        return normalized;
    }

    private String normalizeRequestedBy(String requestedBy) {
        String normalized = requestedBy == null ? "" : requestedBy.trim();
        if (normalized.isBlank()) {
            return "SYSTEM";
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private String normalizeReason(String reason, String defaultReason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) {
            return defaultReason;
        }
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
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
        requireSingleUpdate(inserted, "Liquidity-provider generated-key insert");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Liquidity-provider generated key is missing");
        }
        return key.longValue();
    }

    private void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(
                    operation + " count mismatch: expected=1, actual=" + count
            );
        }
    }

    @FunctionalInterface
    private interface PreparedStatementBinder {
        void bind(PreparedStatement statement) throws java.sql.SQLException;
    }

    private record MarketSymbol(
            String symbol,
            long issuedShares,
            long tradableShares,
            BigDecimal currentPrice,
            boolean marketEnabled,
            String marketStatus
    ) {
        boolean configuredMarket() {
            return marketEnabled
                    && ("OPEN".equals(marketStatus) || "CLOSED".equals(marketStatus));
        }

        boolean pendingRoleSeparatedMarket() {
            return !marketEnabled && "CLOSED".equals(marketStatus);
        }
    }

    private record SourceHolding(
            long accountId,
            String accountStatus,
            String participantCategory,
            long quantity,
            long reservedQuantity,
            BigDecimal averagePrice
    ) {
        long availableQuantity() {
            return Math.max(0L, quantity - reservedQuantity);
        }
    }

    private record MarketParticipant(
            long id,
            String participantType,
            String status,
            String selfTradeGroupId
    ) {
    }

    private record ExistingTransition(
            long id,
            String symbol,
            long mandateId,
            long participantId,
            long liquidityAccountId,
            long sourceAccountId,
            String stage,
            long referenceDailyVolume,
            long seedInventoryQuantity,
            BigDecimal seedCashAmount,
            LocalDate effectiveBusinessDate,
            long policyVersion
    ) {
    }
}
