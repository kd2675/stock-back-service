package stock.back.service.market.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.LiquidityProviderPolicyUpdateRequest;
import stock.back.service.market.vo.LiquidityProviderStatusChangeRequest;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
public class LiquidityProviderControlService {

    private static final BigDecimal MAX_REFERENCE_FLOAT_RATE =
            MarketReferenceVolumeResolver.MAX_FLOAT_RATE;
    private static final BigDecimal MAX_DAILY_LOSS_NAV_RATE = new BigDecimal("0.100000");

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;
    private final MarketLedgerFreezeGuard marketLedgerFreezeGuard;
    private final MarketRoleActivationDateService activationDateService;
    private final MarketRoleOrderCleanupService marketRoleOrderCleanupService;

    public LiquidityProviderControlService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SimulationClockService simulationClockService,
            MarketLedgerFreezeGuard marketLedgerFreezeGuard,
            MarketRoleActivationDateService activationDateService,
            MarketRoleOrderCleanupService marketRoleOrderCleanupService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.simulationClockService = simulationClockService;
        this.marketLedgerFreezeGuard = marketLedgerFreezeGuard;
        this.activationDateService = activationDateService;
        this.marketRoleOrderCleanupService = marketRoleOrderCleanupService;
    }

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public void updatePolicy(
            String symbol,
            LiquidityProviderPolicyUpdateRequest request,
            String changedBy
    ) {
        String normalizedSymbol = requireSymbol(symbol);
        if (request == null) {
            throw StockException.badRequest("Liquidity-provider policy update is required");
        }
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDate activeBusinessDate = marketLedgerFreezeGuard.acquireJdbcMutationPermit(
                "liquidity-provider policy scheduling"
        );
        LocalDate effectiveBusinessDate = activationDateService.resolveNextOpeningDate(
                clock,
                activeBusinessDate
        );

        MandateTarget target = lockMandate(normalizedSymbol);
        if (!"ACTIVE".equals(target.status()) && !"SUSPENDED".equals(target.status())) {
            throw StockException.conflict(
                    "Only an active or suspended liquidity provider can schedule a policy"
            );
        }
        requireDedicatedRole(target, effectiveBusinessDate);

        PolicyValues policy = validatePolicy(target, request);
        LocalDateTime now = clock.simulationDateTime();
        String reason = normalizeReason(
                request.changeReason(),
                "Schedule liquidity-provider policy for the next trading session"
        );
        String actor = normalizeChangedBy(changedBy);
        ScheduledPolicyVersion scheduledPolicy = lockScheduledPolicy(normalizedSymbol);
        if (scheduledPolicy == null) {
            insertScheduledPolicyVersion(
                    target,
                    policy,
                    Math.addExact(target.policyVersion(), 1L),
                    effectiveBusinessDate,
                    reason,
                    actor,
                    "POLICY_UPDATE",
                    target.status(),
                    now
            );
            return;
        }
        if (scheduledPolicy.version() != Math.addExact(target.policyVersion(), 1L)) {
            throw StockException.conflict(
                    "Scheduled liquidity-provider policy version is not aligned with the active policy"
            );
        }
        updateScheduledPolicyVersion(
                scheduledPolicy.id(),
                target,
                policy,
                effectiveBusinessDate,
                reason,
                actor,
                scheduledPolicy.activationAction(),
                scheduledPolicy.targetStatus(),
                now
        );
    }

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public void suspend(
            String symbol,
            LiquidityProviderStatusChangeRequest request,
            String changedBy
    ) {
        String normalizedSymbol = requireSymbol(symbol);
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDate businessDate = marketLedgerFreezeGuard.acquireJdbcMutationPermit(
                "liquidity-provider emergency suspension"
        );
        MandateTarget target = lockMandate(normalizedSymbol);
        if ("SUSPENDED".equals(target.status())) {
            cancelScheduledPolicies(normalizedSymbol);
            marketRoleOrderCleanupService.cancelOpenOrderBookOrders(
                    target.accountId(),
                    "LIQUIDITY_PROVIDER",
                    normalizedSymbol,
                    clock.simulationDateTime()
            );
            return;
        }
        if (!"ACTIVE".equals(target.status())) {
            throw StockException.conflict(
                    "Only an active liquidity provider can be suspended"
            );
        }

        OpenQuantity openQuantity = findOpenQuantity(target);
        long nextPolicyVersion = Math.addExact(target.policyVersion(), 1L);
        LocalDateTime now = clock.simulationDateTime();
        rebaseScheduledPolicyVersion(
                normalizedSymbol,
                nextPolicyVersion,
                Math.addExact(nextPolicyVersion, 1L),
                now
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_liquidity_mandate
                           set status = 'SUSPENDED',
                               next_quote_at = null,
                               policy_version = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'ACTIVE'
                           and policy_version = ?
                        """,
                        nextPolicyVersion,
                        now,
                        target.mandateId(),
                        target.policyVersion()
                ),
                "Liquidity-provider emergency suspension"
        );
        marketRoleOrderCleanupService.cancelOpenOrderBookOrders(
                target.accountId(),
                "LIQUIDITY_PROVIDER",
                normalizedSymbol,
                now
        );
        markDailyStateSuspended(
                target.mandateId(),
                businessDate,
                openQuantity,
                nextPolicyVersion,
                now
        );
        updateTransitionStage(
                target.mandateId(),
                "SUSPENDED",
                nextPolicyVersion,
                now
        );
        retireActivePolicy(normalizedSymbol, now);
        insertPolicyVersion(
                target,
                target.policy(),
                nextPolicyVersion,
                businessDate,
                "SUSPENDED",
                normalizeReason(
                        request == null ? null : request.changeReason(),
                        "Suspend liquidity provider and cancel open orders"
                ),
                normalizeChangedBy(changedBy),
                now
        );
    }

    @Transactional(transactionManager = "pubJdbcTransactionManager")
    public void resume(
            String symbol,
            LiquidityProviderStatusChangeRequest request,
            String changedBy
    ) {
        String normalizedSymbol = requireSymbol(symbol);
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDate activeBusinessDate = marketLedgerFreezeGuard.acquireJdbcMutationPermit(
                "liquidity-provider resume"
        );
        LocalDate effectiveBusinessDate = activationDateService.resolveNextOpeningDate(
                clock,
                activeBusinessDate
        );

        MandateTarget target = lockMandate(normalizedSymbol);
        if ("ACTIVE".equals(target.status())) {
            return;
        }
        if (!"SUSPENDED".equals(target.status())) {
            throw StockException.conflict(
                    "Only a suspended liquidity provider can be resumed"
            );
        }
        requireDedicatedRole(target, effectiveBusinessDate);
        requireNoOpenOrders(target);
        LocalDateTime now = clock.simulationDateTime();
        String reason = normalizeReason(
                request == null ? null : request.changeReason(),
                "Schedule liquidity-provider resume for the next opening"
        );
        String actor = normalizeChangedBy(changedBy);
        ScheduledPolicyVersion scheduledPolicy = lockScheduledPolicy(normalizedSymbol);
        if (scheduledPolicy == null) {
            insertScheduledPolicyVersion(
                    target,
                    target.policy(),
                    Math.addExact(target.policyVersion(), 1L),
                    effectiveBusinessDate,
                    reason,
                    actor,
                    "RESUME",
                    "ACTIVE",
                    now
            );
            return;
        }
        updateScheduledPolicyLifecycle(
                scheduledPolicy.id(),
                effectiveBusinessDate,
                reason,
                actor,
                "RESUME",
                "ACTIVE",
                now
        );
    }

    private MandateTarget lockMandate(String symbol) {
        return jdbcClient.sql(
                        """
                        select mandate.id,
                               mandate.mandate_code,
                               mandate.account_id,
                               mandate.execution_mode,
                               mandate.status,
                               mandate.policy_version,
                               mandate.target_spread_ticks,
                               mandate.max_spread_ticks,
                               mandate.max_order_quantity,
                               mandate.reference_daily_volume,
                               mandate.target_open_participation_rate,
                               mandate.max_open_participation_rate,
                               mandate.max_single_order_participation_rate,
                               mandate.external_depth_levels,
                               mandate.max_external_depth_participation_rate,
                               mandate.daily_execution_participation_rate,
                               mandate.daily_submission_multiplier,
                               mandate.target_inventory_quantity,
                               mandate.inventory_band_quantity,
                               mandate.inventory_skew_ticks,
                               mandate.primary_regime_weight,
                               mandate.liquidity_size_sensitivity,
                               mandate.volatility_spread_max_ticks,
                               mandate.price_regime_max_skew_ticks,
                               mandate.passive_only,
                               mandate.minimum_quote_lifetime_seconds,
                               mandate.reprice_threshold_ticks,
                               mandate.order_ttl_seconds,
                               mandate.quote_interval_seconds,
                               mandate.daily_loss_limit_amount,
                               instrument.tradable_shares,
                               account.status as account_status,
                               account.participant_category,
                               account.self_trade_group_id,
                               account.cash_balance,
                               coalesce(holding.quantity, 0) as holding_quantity,
                               coalesce(holding.reserved_quantity, 0) as reserved_quantity,
                               coalesce(price.current_price, 0) as current_price
                          from stock_liquidity_mandate mandate
                          join stock_order_book_instrument instrument
                            on instrument.symbol = mandate.symbol
                          join stock_account account
                            on account.id = mandate.account_id
                          left join stock_holding holding
                            on holding.account_id = mandate.account_id
                           and holding.symbol = mandate.symbol
                          left join stock_price price
                            on price.symbol = mandate.symbol
                         where mandate.symbol = ?
                         for update
                        """
                )
                .param(symbol)
                .query((rs, rowNum) -> new MandateTarget(
                        rs.getLong("id"),
                        rs.getString("mandate_code"),
                        symbol,
                        rs.getLong("account_id"),
                        rs.getString("execution_mode"),
                        rs.getString("status"),
                        rs.getLong("policy_version"),
                        rs.getLong("tradable_shares"),
                        rs.getString("account_status"),
                        rs.getString("participant_category"),
                        rs.getString("self_trade_group_id"),
                        money(rs.getBigDecimal("cash_balance")),
                        rs.getLong("holding_quantity"),
                        rs.getLong("reserved_quantity"),
                        money(rs.getBigDecimal("current_price")),
                        new PolicyValues(
                                rs.getInt("target_spread_ticks"),
                                rs.getInt("max_spread_ticks"),
                                rs.getLong("max_order_quantity"),
                                rs.getLong("reference_daily_volume"),
                                rate(rs.getBigDecimal("target_open_participation_rate")),
                                rate(rs.getBigDecimal("max_open_participation_rate")),
                                rate(rs.getBigDecimal("max_single_order_participation_rate")),
                                rs.getInt("external_depth_levels"),
                                rate(rs.getBigDecimal("max_external_depth_participation_rate")),
                                rate(rs.getBigDecimal("daily_execution_participation_rate")),
                                rs.getBigDecimal("daily_submission_multiplier"),
                                rs.getLong("target_inventory_quantity"),
                                rs.getLong("inventory_band_quantity"),
                                rs.getInt("inventory_skew_ticks"),
                                rate(rs.getBigDecimal("primary_regime_weight")),
                                rate(rs.getBigDecimal("liquidity_size_sensitivity")),
                                rs.getInt("volatility_spread_max_ticks"),
                                rs.getInt("price_regime_max_skew_ticks"),
                                rs.getBoolean("passive_only"),
                                rs.getInt("minimum_quote_lifetime_seconds"),
                                rs.getInt("reprice_threshold_ticks"),
                                rs.getInt("order_ttl_seconds"),
                                rs.getInt("quote_interval_seconds"),
                                money(rs.getBigDecimal("daily_loss_limit_amount"))
                        )
                ))
                .optional()
                .orElseThrow(() -> StockException.notFound(
                        "Liquidity-provider mandate not found: " + symbol
                ));
    }

    private ScheduledPolicyVersion lockScheduledPolicy(String symbol) {
        return jdbcClient.sql(
                        """
                        select id, version_no, config_json
                          from stock_market_policy_version
                         where policy_scope = 'LIQUIDITY_MANDATE'
                           and scope_key = ?
                           and status = 'SCHEDULED'
                         for update
                        """
                )
                .param(symbol)
                .query((rs, rowNum) -> {
                    String configJson = rs.getString("config_json");
                    JsonNode root = readPolicyConfig(configJson);
                    String activationAction = textOrDefault(
                            root,
                            "activationAction",
                            "POLICY_UPDATE"
                    );
                    String targetStatus = textOrDefault(
                            root,
                            "targetStatus",
                            textOrDefault(root, "status", "SUSPENDED")
                    );
                    return new ScheduledPolicyVersion(
                            rs.getLong("id"),
                            rs.getLong("version_no"),
                            configJson,
                            activationAction,
                            targetStatus
                    );
                })
                .optional()
                .orElse(null);
    }

    private void requireDedicatedRole(MandateTarget target, LocalDate businessDate) {
        if (!"LIVE".equals(target.executionMode())
                || !"ACTIVE".equals(target.accountStatus())
                || !"LIQUIDITY_PROVIDER".equals(target.participantCategory())
                || target.selfTradeGroupId() == null
                || target.selfTradeGroupId().isBlank()
                || target.cashBalance().signum() < 0
                || target.holdingQuantity() < 0
                || target.reservedQuantity() < 0
                || target.reservedQuantity() > target.holdingQuantity()) {
            throw StockException.conflict(
                    "Liquidity-provider dedicated account is not eligible"
            );
        }
        Boolean roleEligible = jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_liquidity_mandate mandate
                              join stock_market_participant participant
                                on participant.id = mandate.participant_id
                              join stock_market_participant_account mapping
                                on mapping.participant_id = mandate.participant_id
                               and mapping.account_id = mandate.account_id
                             where mandate.id = ?
                               and participant.status = 'ACTIVE'
                               and participant.participant_type = 'LIQUIDITY_PROVIDER'
                               and participant.self_trade_group_id = ?
                               and mapping.status = 'ACTIVE'
                               and mapping.account_role = 'LIQUIDITY_PROVIDER'
                               and mapping.desk_code = mandate.symbol
                               and mapping.effective_from <= ?
                               and (
                                   mapping.effective_to is null
                                   or mapping.effective_to >= ?
                               )
                        )
                        """
                )
                .param(target.mandateId())
                .param(target.selfTradeGroupId())
                .param(businessDate)
                .param(businessDate)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(roleEligible)) {
            throw StockException.conflict(
                    "Liquidity-provider participant role is not effective"
            );
        }
    }

    private void requireNoOpenOrders(MandateTarget target) {
        Long count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_order
                         where account_id = ?
                           and symbol = ?
                           and market_type = 'ORDER_BOOK'
                           and status in ('PENDING', 'PARTIALLY_FILLED')
                           and quantity > filled_quantity
                        """
                )
                .param(target.accountId())
                .param(target.symbol())
                .query(Long.class)
                .single();
        if (count != null && count > 0L) {
            throw StockException.conflict(
                    "Liquidity-provider open orders must be cancelled before this operation"
            );
        }
    }

    private PolicyValues validatePolicy(
            MandateTarget target,
            LiquidityProviderPolicyUpdateRequest request
    ) {
        int targetSpread = required(request.targetSpreadTicks(), "Target spread ticks");
        int maxSpread = required(request.maxSpreadTicks(), "Maximum spread ticks");
        long maxOrder = required(request.maxOrderQuantity(), "Maximum order quantity");
        long referenceVolume = required(request.referenceDailyVolume(), "Reference daily volume");
        BigDecimal targetOpenRate = required(
                request.targetOpenParticipationRate(),
                "Target open participation rate"
        );
        BigDecimal maxOpenRate = required(
                request.maxOpenParticipationRate(),
                "Maximum open participation rate"
        );
        BigDecimal singleOrderRate = required(
                request.maxSingleOrderParticipationRate(),
                "Maximum single-order participation rate"
        );
        int externalDepthLevels = required(request.externalDepthLevels(), "External depth levels");
        BigDecimal externalDepthRate = required(
                request.maxExternalDepthParticipationRate(),
                "Maximum external-depth participation rate"
        );
        BigDecimal dailyExecutionRate = required(
                request.dailyExecutionParticipationRate(),
                "Daily execution participation rate"
        );
        BigDecimal submissionMultiplier = required(
                request.dailySubmissionMultiplier(),
                "Daily submission multiplier"
        );
        long targetInventory = required(
                request.targetInventoryQuantity(),
                "Target inventory quantity"
        );
        long inventoryBand = required(
                request.inventoryBandQuantity(),
                "Inventory band quantity"
        );
        int inventorySkewTicks = required(
                request.inventorySkewTicks(),
                "Inventory skew ticks"
        );
        int volatilitySpreadTicks = required(
                request.volatilitySpreadMaxTicks(),
                "Volatility spread maximum ticks"
        );
        int priceRegimeSkewTicks = required(
                request.priceRegimeMaxSkewTicks(),
                "Price-regime skew maximum ticks"
        );
        int minimumQuoteLifetime = required(
                request.minimumQuoteLifetimeSeconds(),
                "Minimum quote lifetime"
        );
        int repriceThreshold = required(
                request.repriceThresholdTicks(),
                "Reprice threshold ticks"
        );
        int orderTtl = required(request.orderTtlSeconds(), "Order TTL");
        int quoteInterval = required(request.quoteIntervalSeconds(), "Quote interval");
        BigDecimal dailyLossLimit = money(required(
                request.dailyLossLimitAmount(),
                "Daily loss limit"
        ));

        if (targetSpread < 1 || targetSpread > 50
                || maxSpread < targetSpread || maxSpread > 100) {
            throw StockException.badRequest(
                    "Spread ticks must satisfy 1 <= target <= maximum <= 100"
            );
        }
        long maxReferenceVolume = BigDecimal.valueOf(target.tradableShares())
                .multiply(MAX_REFERENCE_FLOAT_RATE)
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
        if (referenceVolume <= 0L || referenceVolume > Math.max(1L, maxReferenceVolume)) {
            throw StockException.badRequest(
                    "Reference daily volume must be positive and at most 200% of tradable shares"
            );
        }
        requireRate(targetOpenRate, BigDecimal.ZERO, new BigDecimal("0.100000"),
                "Target open participation rate");
        requireRate(maxOpenRate, targetOpenRate, new BigDecimal("0.200000"),
                "Maximum open participation rate");
        requireRate(singleOrderRate, BigDecimal.ZERO, targetOpenRate,
                "Maximum single-order participation rate");
        if (externalDepthLevels < 1 || externalDepthLevels > 10) {
            throw StockException.badRequest("External depth levels must be between 1 and 10");
        }
        requireRate(externalDepthRate, BigDecimal.ZERO, new BigDecimal("0.250000"),
                "Maximum external-depth participation rate");
        requireRate(dailyExecutionRate, BigDecimal.ZERO, new BigDecimal("0.300000"),
                "Daily execution participation rate");
        if (submissionMultiplier.compareTo(BigDecimal.ONE) < 0
                || submissionMultiplier.compareTo(BigDecimal.TEN) > 0) {
            throw StockException.badRequest(
                    "Daily submission multiplier must be between 1 and 10"
            );
        }
        long rateOrderLimit = BigDecimal.valueOf(referenceVolume)
                .multiply(singleOrderRate)
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
        if (maxOrder <= 0L
                || maxOrder > Math.max(1L, rateOrderLimit)
                || maxOrder > inventoryBand) {
            throw StockException.badRequest(
                    "Maximum order quantity must fit both the single-order rate and inventory band"
            );
        }
        if (targetInventory < 0L
                || inventoryBand <= 0L
                || targetInventory > target.tradableShares()
                || inventoryBand > target.tradableShares()
                || targetInventory > target.tradableShares() - inventoryBand) {
            throw StockException.badRequest(
                    "Target inventory plus its band must fit within tradable shares"
            );
        }
        if (inventorySkewTicks < 0 || inventorySkewTicks > 50
                || volatilitySpreadTicks < 0 || volatilitySpreadTicks > 50
                || priceRegimeSkewTicks < 0 || priceRegimeSkewTicks > 5) {
            throw StockException.badRequest(
                    "Inventory and regime tick controls are outside the supported range"
            );
        }
        if (minimumQuoteLifetime < 10 || minimumQuoteLifetime > 1800
                || repriceThreshold < 1 || repriceThreshold > 20
                || orderTtl < minimumQuoteLifetime || orderTtl > 7200
                || quoteInterval < 10 || quoteInterval > 600
                || quoteInterval > orderTtl) {
            throw StockException.badRequest(
                    "Quote timing must satisfy lifetime <= TTL and interval <= TTL"
            );
        }
        BigDecimal currentNetAssetValue = target.cashBalance().add(
                target.currentPrice().multiply(BigDecimal.valueOf(target.holdingQuantity()))
        );
        BigDecimal maxDailyLoss = currentNetAssetValue
                .multiply(MAX_DAILY_LOSS_NAV_RATE)
                .max(BigDecimal.ONE)
                .setScale(2, RoundingMode.HALF_UP);
        if (dailyLossLimit.signum() <= 0
                || dailyLossLimit.compareTo(maxDailyLoss) > 0) {
            throw StockException.badRequest(
                    "Daily loss limit must be positive and at most 10% of current net assets"
            );
        }

        PolicyValues current = target.policy();
        return new PolicyValues(
                targetSpread,
                maxSpread,
                maxOrder,
                referenceVolume,
                rate(targetOpenRate),
                rate(maxOpenRate),
                rate(singleOrderRate),
                externalDepthLevels,
                rate(externalDepthRate),
                rate(dailyExecutionRate),
                submissionMultiplier.setScale(4, RoundingMode.HALF_UP),
                targetInventory,
                inventoryBand,
                inventorySkewTicks,
                current.primaryRegimeWeight(),
                current.liquiditySizeSensitivity(),
                volatilitySpreadTicks,
                priceRegimeSkewTicks,
                true,
                minimumQuoteLifetime,
                repriceThreshold,
                orderTtl,
                quoteInterval,
                dailyLossLimit
        );
    }

    private OpenQuantity findOpenQuantity(MandateTarget target) {
        return jdbcClient.sql(
                        """
                        select coalesce(sum(
                                   case when side = 'BUY'
                                        then quantity - filled_quantity else 0 end
                               ), 0) as buy_quantity,
                               coalesce(sum(
                                   case when side = 'SELL'
                                        then quantity - filled_quantity else 0 end
                               ), 0) as sell_quantity
                          from stock_order
                         where account_id = ?
                           and symbol = ?
                           and market_type = 'ORDER_BOOK'
                           and status in ('PENDING', 'PARTIALLY_FILLED')
                           and quantity > filled_quantity
                        """
                )
                .param(target.accountId())
                .param(target.symbol())
                .query((rs, rowNum) -> new OpenQuantity(
                        rs.getLong("buy_quantity"),
                        rs.getLong("sell_quantity")
                ))
                .single();
    }

    private void markDailyStateSuspended(
            long mandateId,
            LocalDate businessDate,
            OpenQuantity openQuantity,
            long policyVersion,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                update stock_liquidity_daily_state
                   set cancelled_buy_quantity = cancelled_buy_quantity + ?,
                       cancelled_sell_quantity = cancelled_sell_quantity + ?,
                       target_buy_open_quantity = 0,
                       target_sell_open_quantity = 0,
                       last_open_buy_quantity = 0,
                       last_open_sell_quantity = 0,
                       state_status = 'HALTED',
                       gate_reason = 'ADMIN_SUSPENDED',
                       policy_version = ?,
                       version = version + 1,
                       updated_at = ?
                 where simulation_trade_date = ?
                   and mandate_id = ?
                """,
                openQuantity.buyQuantity(),
                openQuantity.sellQuantity(),
                policyVersion,
                now,
                businessDate,
                mandateId
        );
    }

    private void updateTransitionStage(
            long mandateId,
            String stage,
            long policyVersion,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_liquidity_transition
                           set stage = ?,
                               policy_version = ?,
                               updated_at = ?
                         where mandate_id = ?
                        """,
                        stage,
                        policyVersion,
                        now,
                        mandateId
                ),
                "Liquidity-provider transition stage update"
        );
    }

    private void rebaseScheduledPolicyVersion(
            String symbol,
            long conflictingVersion,
            long rebasedVersion,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set version_no = ?,
                       updated_at = ?
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = ?
                   and status = 'SCHEDULED'
                   and version_no = ?
                """,
                rebasedVersion,
                now,
                symbol,
                conflictingVersion
        );
    }

    private void retireActivePolicy(String symbol, LocalDateTime now) {
        jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set status = 'RETIRED',
                       updated_at = ?
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = ?
                   and status = 'ACTIVE'
                """,
                now,
                symbol
        );
    }

    private void insertScheduledPolicyVersion(
            MandateTarget target,
            PolicyValues policy,
            long version,
            LocalDate effectiveBusinessDate,
            String reason,
            String changedBy,
            String activationAction,
            String targetStatus,
            LocalDateTime now
    ) {
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
                        target.symbol(),
                        version,
                        effectiveBusinessDate,
                        policyConfigJson(
                                target,
                                policy,
                                target.status(),
                                activationAction,
                                targetStatus
                        ),
                        reason,
                        changedBy,
                        now,
                        now
                ),
                "Scheduled liquidity-provider policy insert"
        );
    }

    private void updateScheduledPolicyVersion(
            long scheduledPolicyId,
            MandateTarget target,
            PolicyValues policy,
            LocalDate effectiveBusinessDate,
            String reason,
            String changedBy,
            String activationAction,
            String targetStatus,
            LocalDateTime now
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_policy_version
                           set effective_business_date = ?,
                               config_json = ?,
                               change_reason = ?,
                               changed_by = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'SCHEDULED'
                        """,
                        effectiveBusinessDate,
                        policyConfigJson(
                                target,
                                policy,
                                target.status(),
                                activationAction,
                                targetStatus
                        ),
                        reason,
                        changedBy,
                        now,
                        scheduledPolicyId
                ),
                "Scheduled liquidity-provider policy update"
        );
    }

    private void insertPolicyVersion(
            MandateTarget target,
            PolicyValues policy,
            long version,
            LocalDate businessDate,
            String status,
            String reason,
            String changedBy,
            LocalDateTime now
    ) {
        String configJson = policyConfigJson(target, policy, status);
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        insert into stock_market_policy_version(
                            policy_scope, scope_key, version_no,
                            effective_business_date, status, config_json,
                            change_reason, changed_by, created_at, updated_at
                        ) values (
                            'LIQUIDITY_MANDATE', ?, ?, ?, 'ACTIVE', ?,
                            ?, ?, ?, ?
                        )
                        """,
                        target.symbol(),
                        version,
                        businessDate,
                        configJson,
                        reason,
                        changedBy,
                        now,
                        now
                ),
                "Liquidity-provider policy version"
        );
    }

    private String policyConfigJson(
            MandateTarget target,
            PolicyValues policy,
            String mandateStatus
    ) {
        return policyConfigJson(
                target,
                policy,
                mandateStatus,
                "POLICY_UPDATE",
                mandateStatus
        );
    }

    private String policyConfigJson(
            MandateTarget target,
            PolicyValues policy,
            String mandateStatus,
            String activationAction,
            String targetStatus
    ) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("preset", "INDEPENDENT_LIQUIDITY_PROVIDER_V2");
        config.put("symbol", target.symbol());
        config.put("executionMode", target.executionMode());
        config.put("status", mandateStatus);
        config.put("activationAction", activationAction);
        config.put("targetStatus", targetStatus);
        config.put("targetSpreadTicks", policy.targetSpreadTicks());
        config.put("maxSpreadTicks", policy.maxSpreadTicks());
        config.put("maxOrderQuantity", policy.maxOrderQuantity());
        config.put("referenceDailyVolume", policy.referenceDailyVolume());
        config.put("targetOpenParticipationRate", policy.targetOpenParticipationRate());
        config.put("maxOpenParticipationRate", policy.maxOpenParticipationRate());
        config.put("maxSingleOrderParticipationRate", policy.maxSingleOrderParticipationRate());
        config.put("externalDepthLevels", policy.externalDepthLevels());
        config.put("maxExternalDepthParticipationRate", policy.maxExternalDepthParticipationRate());
        config.put("dailyExecutionParticipationRate", policy.dailyExecutionParticipationRate());
        config.put("dailySubmissionMultiplier", policy.dailySubmissionMultiplier());
        config.put("targetInventoryQuantity", policy.targetInventoryQuantity());
        config.put("inventoryBandQuantity", policy.inventoryBandQuantity());
        config.put("inventorySkewTicks", policy.inventorySkewTicks());
        config.put("primaryRegimeWeight", policy.primaryRegimeWeight());
        config.put("liquiditySizeSensitivity", policy.liquiditySizeSensitivity());
        config.put("volatilitySpreadMaxTicks", policy.volatilitySpreadMaxTicks());
        config.put("priceRegimeMaxSkewTicks", policy.priceRegimeMaxSkewTicks());
        config.put("passiveOnly", policy.passiveOnly());
        config.put("minimumQuoteLifetimeSeconds", policy.minimumQuoteLifetimeSeconds());
        config.put("repriceThresholdTicks", policy.repriceThresholdTicks());
        config.put("orderTtlSeconds", policy.orderTtlSeconds());
        config.put("quoteIntervalSeconds", policy.quoteIntervalSeconds());
        config.put("dailyLossLimitAmount", policy.dailyLossLimitAmount());
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Liquidity-provider policy JSON serialization failed",
                    ex
            );
        }
    }

    private void updateScheduledPolicyLifecycle(
            long policyId,
            LocalDate effectiveBusinessDate,
            String reason,
            String changedBy,
            String activationAction,
            String targetStatus,
            LocalDateTime now
    ) {
        String configJson = jdbcClient.sql(
                        """
                        select config_json
                          from stock_market_policy_version
                         where id = ?
                           and status = 'SCHEDULED'
                         for update
                        """
                )
                .param(policyId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> StockException.conflict(
                        "Scheduled liquidity-provider policy is missing"
                ));
        ObjectNode root = requireObjectPolicyConfig(configJson);
        root.put("activationAction", activationAction);
        root.put("targetStatus", targetStatus);
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_policy_version
                           set effective_business_date = ?,
                               config_json = ?,
                               change_reason = ?,
                               changed_by = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'SCHEDULED'
                        """,
                        effectiveBusinessDate,
                        writePolicyConfig(root),
                        reason,
                        changedBy,
                        now,
                        policyId
                ),
                "Scheduled liquidity-provider lifecycle update"
        );
    }

    private void cancelScheduledPolicies(String symbol) {
        jdbcTemplate.update(
                """
                delete from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = ?
                   and status = 'SCHEDULED'
                """,
                symbol
        );
    }

    private JsonNode readPolicyConfig(String configJson) {
        try {
            JsonNode root = objectMapper.readTree(configJson);
            if (root != null && root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
            if (root == null || !root.isObject()) {
                throw new IllegalStateException(
                        "Scheduled liquidity-provider policy JSON must be an object"
                );
            }
            return root;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Scheduled liquidity-provider policy JSON is invalid",
                    ex
            );
        }
    }

    private ObjectNode requireObjectPolicyConfig(String configJson) {
        return (ObjectNode) readPolicyConfig(configJson);
    }

    private String textOrDefault(JsonNode root, String fieldName, String defaultValue) {
        JsonNode value = root.get(fieldName);
        return value == null || value.isNull() || value.asText().isBlank()
                ? defaultValue
                : value.asText();
    }

    private String writePolicyConfig(JsonNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Liquidity-provider policy JSON serialization failed",
                    ex
            );
        }
    }

    private String requireSymbol(String symbol) {
        String normalized = MarketTextNormalizer.symbol(symbol);
        if (normalized.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        return normalized;
    }

    private String normalizeReason(String reason, String fallback) {
        String normalized = reason == null ? "" : reason.trim();
        return truncate(normalized.isBlank() ? fallback : normalized, 500);
    }

    private String normalizeChangedBy(String changedBy) {
        String normalized = changedBy == null ? "" : changedBy.trim();
        return truncate(normalized.isBlank() ? "SYSTEM" : normalized, 64);
    }

    private void requireRate(
            BigDecimal value,
            BigDecimal lowerExclusiveOrInclusive,
            BigDecimal upperInclusive,
            String fieldName
    ) {
        boolean lowerInvalid = lowerExclusiveOrInclusive.signum() == 0
                ? value.compareTo(lowerExclusiveOrInclusive) <= 0
                : value.compareTo(lowerExclusiveOrInclusive) < 0;
        if (lowerInvalid || value.compareTo(upperInclusive) > 0) {
            throw StockException.badRequest(fieldName + " is outside the supported range");
        }
    }

    private int required(Integer value, String fieldName) {
        if (value == null) {
            throw StockException.badRequest(fieldName + " is required");
        }
        return value;
    }

    private long required(Long value, String fieldName) {
        if (value == null) {
            throw StockException.badRequest(fieldName + " is required");
        }
        return value;
    }

    private BigDecimal required(BigDecimal value, String fieldName) {
        if (value == null) {
            throw StockException.badRequest(fieldName + " is required");
        }
        return value;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(6)
                : value.setScale(6, RoundingMode.HALF_UP);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(
                    operation + " count mismatch: expected=1, actual=" + count
            );
        }
    }

    private record OpenQuantity(
            long buyQuantity,
            long sellQuantity
    ) {
    }

    private record ScheduledPolicyVersion(
            long id,
            long version,
            String configJson,
            String activationAction,
            String targetStatus
    ) {
    }

    private record MandateTarget(
            long mandateId,
            String mandateCode,
            String symbol,
            long accountId,
            String executionMode,
            String status,
            long policyVersion,
            long tradableShares,
            String accountStatus,
            String participantCategory,
            String selfTradeGroupId,
            BigDecimal cashBalance,
            long holdingQuantity,
            long reservedQuantity,
            BigDecimal currentPrice,
            PolicyValues policy
    ) {
    }

    private record PolicyValues(
            int targetSpreadTicks,
            int maxSpreadTicks,
            long maxOrderQuantity,
            long referenceDailyVolume,
            BigDecimal targetOpenParticipationRate,
            BigDecimal maxOpenParticipationRate,
            BigDecimal maxSingleOrderParticipationRate,
            int externalDepthLevels,
            BigDecimal maxExternalDepthParticipationRate,
            BigDecimal dailyExecutionParticipationRate,
            BigDecimal dailySubmissionMultiplier,
            long targetInventoryQuantity,
            long inventoryBandQuantity,
            int inventorySkewTicks,
            BigDecimal primaryRegimeWeight,
            BigDecimal liquiditySizeSensitivity,
            int volatilitySpreadMaxTicks,
            int priceRegimeMaxSkewTicks,
            boolean passiveOnly,
            int minimumQuoteLifetimeSeconds,
            int repriceThresholdTicks,
            int orderTtlSeconds,
            int quoteIntervalSeconds,
            BigDecimal dailyLossLimitAmount
    ) {
    }
}
