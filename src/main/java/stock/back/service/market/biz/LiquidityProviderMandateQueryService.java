package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.LiquidityProviderMandateResponse;

@Service
public class LiquidityProviderMandateQueryService {

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);

    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;

    public LiquidityProviderMandateQueryService(
            JdbcClient jdbcClient,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = jdbcClient;
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true)
    public List<LiquidityProviderMandateResponse> getMandates() {
        LocalDate simulationTradeDate = simulationClockService.currentSnapshot().simulationDate();
        return jdbcClient.sql(
                        """
                        select mandate.id as mandate_id,
                               mandate.mandate_code,
                               mandate.symbol,
                               mandate.execution_mode,
                               mandate.status as mandate_status,
                               mandate.contract_start_date,
                               mandate.contract_end_date,
                               mandate.next_quote_at,
                               mandate.policy_version as mandate_policy_version,
                               mandate.target_spread_ticks,
                               mandate.max_spread_ticks,
                               mandate.max_order_quantity,
                               mandate.reference_daily_volume as mandate_reference_daily_volume,
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
                               participant.id as participant_id,
                               participant.participant_code,
                               participant.participant_type,
                               participant.status as participant_status,
                               participant.self_trade_group_id as participant_self_trade_group_id,
                               account.id as account_id,
                               account.account_code,
                               account.status as account_status,
                               account.participant_category,
                               account.self_trade_group_id as account_self_trade_group_id,
                               account.cash_balance,
                               role_mapping.account_role,
                               role_mapping.status as role_mapping_status,
                               role_mapping.effective_from as role_effective_from,
                               role_mapping.effective_to as role_effective_to,
                               coalesce(holding.quantity, 0) as holding_quantity,
                               coalesce(holding.reserved_quantity, 0) as reserved_sell_quantity,
                               coalesce(holding.average_price, 0) as average_price,
                               coalesce(price.current_price, 0) as current_price,
                               (
                                   select count(*)
                                     from stock_order open_order
                                    where open_order.account_id = mandate.account_id
                                      and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                                      and open_order.quantity > open_order.filled_quantity
                                      and (
                                          open_order.origin_type is null
                                          or open_order.origin_type <> 'LIQUIDITY_PROVIDER'
                                          or open_order.symbol <> mandate.symbol
                                          or open_order.market_type <> 'ORDER_BOOK'
                                          or open_order.order_type <> 'LIMIT'
                                      )
                               ) as non_liquidity_open_order_count,
                               (
                                   select count(*)
                                     from stock_holding unmanaged_holding
                                    where unmanaged_holding.account_id = mandate.account_id
                                      and unmanaged_holding.symbol <> mandate.symbol
                                      and (
                                          unmanaged_holding.quantity > 0
                                          or unmanaged_holding.reserved_quantity > 0
                                      )
                               ) as unmanaged_holding_count,
                               daily_state.simulation_trade_date as state_trade_date,
                               daily_state.reference_daily_volume as state_reference_daily_volume,
                               daily_state.execution_quantity_limit,
                               daily_state.submission_quantity_limit,
                               daily_state.submitted_buy_quantity,
                               daily_state.submitted_sell_quantity,
                               daily_state.submitted_buy_amount,
                               daily_state.submitted_sell_amount,
                               daily_state.cancelled_buy_quantity,
                               daily_state.cancelled_sell_quantity,
                               daily_state.executed_buy_quantity,
                               daily_state.executed_sell_quantity,
                               daily_state.executed_buy_amount,
                               daily_state.executed_sell_amount,
                               daily_state.realized_profit,
                               daily_state.unrealized_profit,
                               daily_state.opening_net_asset_value,
                               daily_state.current_net_asset_value,
                               daily_state.risk_profit,
                               daily_state.target_buy_open_quantity,
                               daily_state.target_sell_open_quantity,
                               daily_state.last_open_buy_quantity,
                               daily_state.last_open_sell_quantity,
                               daily_state.external_buy_depth_quantity,
                               daily_state.external_sell_depth_quantity,
                               daily_state.last_bid_price,
                               daily_state.last_ask_price,
                               daily_state.last_inventory_quantity,
                               daily_state.last_projected_inventory_quantity,
                               daily_state.blended_price_pressure,
                               daily_state.blended_volatility_pressure,
                               daily_state.blended_liquidity_pressure,
                               daily_state.state_status,
                               daily_state.gate_reason,
                               daily_state.quote_run_count,
                               daily_state.limit_breached,
                               daily_state.policy_version as state_policy_version,
                               daily_state.version as state_version,
                               daily_state.updated_at as state_updated_at,
                               transition.id as transition_id,
                               transition.transition_key,
                               transition.stage as transition_stage,
                               transition.source_account_id,
                               transition.legacy_account_id,
                               transition.reference_daily_volume
                                   as transition_reference_daily_volume,
                               transition.seed_inventory_quantity,
                               transition.seed_cash_amount,
                               transition.transferred_inventory_quantity,
                               transition.transferred_cash_amount,
                               transition.effective_business_date
                                   as transition_effective_business_date,
                               transition.legacy_disabled_at,
                               transition.legacy_retired_at,
                               transition.activated_at,
                               transition.requested_by,
                               transition.change_reason,
                               transition.policy_version as transition_policy_version,
                               transition.created_at as transition_created_at,
                               transition.updated_at as transition_updated_at
                          from stock_liquidity_mandate mandate
                          join stock_market_participant participant
                            on participant.id = mandate.participant_id
                          join stock_account account
                            on account.id = mandate.account_id
                          left join stock_market_participant_account role_mapping
                            on role_mapping.participant_id = mandate.participant_id
                           and role_mapping.account_id = mandate.account_id
                          left join stock_holding holding
                            on holding.account_id = mandate.account_id
                           and holding.symbol = mandate.symbol
                          left join stock_price price
                            on price.symbol = mandate.symbol
                          left join stock_liquidity_daily_state daily_state
                            on daily_state.mandate_id = mandate.id
                           and daily_state.simulation_trade_date = :simulationTradeDate
                          left join stock_liquidity_transition transition
                            on transition.mandate_id = mandate.id
                         order by mandate.symbol asc, mandate.id asc
                        """
                )
                .param("simulationTradeDate", simulationTradeDate)
                .query((rs, rowNum) -> mapMandate(rs, simulationTradeDate))
                .list();
    }

    @Transactional(readOnly = true)
    public LiquidityProviderMandateResponse getMandate(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        return getMandates().stream()
                .filter(mandate -> normalizedSymbol.equals(mandate.symbol()))
                .findFirst()
                .orElseThrow(() -> StockException.notFound(
                        "Liquidity-provider mandate not found: " + normalizedSymbol
                ));
    }

    private LiquidityProviderMandateResponse mapMandate(
            ResultSet rs,
            LocalDate simulationTradeDate
    ) throws SQLException {
        long holdingQuantity = rs.getLong("holding_quantity");
        long reservedSellQuantity = rs.getLong("reserved_sell_quantity");
        BigDecimal currentPrice = money(rs.getBigDecimal("current_price"));
        long nonLiquidityOpenOrderCount = rs.getLong("non_liquidity_open_order_count");
        long unmanagedHoldingCount = rs.getLong("unmanaged_holding_count");
        String roleEligibilityIssue = roleEligibilityIssue(
                rs,
                simulationTradeDate,
                holdingQuantity,
                reservedSellQuantity,
                nonLiquidityOpenOrderCount,
                unmanagedHoldingCount
        );
        LiquidityProviderMandateResponse.Account account =
                new LiquidityProviderMandateResponse.Account(
                        rs.getLong("participant_id"),
                        rs.getString("participant_code"),
                        rs.getString("participant_type"),
                        rs.getString("participant_status"),
                        rs.getString("participant_self_trade_group_id"),
                        rs.getLong("account_id"),
                        rs.getString("account_code"),
                        rs.getString("account_status"),
                        rs.getString("participant_category"),
                        rs.getString("account_self_trade_group_id"),
                        rs.getString("account_role"),
                        rs.getString("role_mapping_status"),
                        localDate(rs, "role_effective_from"),
                        localDate(rs, "role_effective_to"),
                        money(rs.getBigDecimal("cash_balance")),
                        holdingQuantity,
                        reservedSellQuantity,
                        Math.max(0L, holdingQuantity - reservedSellQuantity),
                        money(rs.getBigDecimal("average_price")),
                        currentPrice,
                        currentPrice.multiply(BigDecimal.valueOf(Math.max(0L, holdingQuantity)))
                                .setScale(2, RoundingMode.HALF_UP),
                        nonLiquidityOpenOrderCount,
                        unmanagedHoldingCount
                );
        LiquidityProviderMandateResponse.Policy policy =
                new LiquidityProviderMandateResponse.Policy(
                        rs.getInt("target_spread_ticks"),
                        rs.getInt("max_spread_ticks"),
                        rs.getLong("max_order_quantity"),
                        rs.getLong("mandate_reference_daily_volume"),
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
                );
        return new LiquidityProviderMandateResponse(
                rs.getLong("mandate_id"),
                rs.getString("mandate_code"),
                rs.getString("symbol"),
                rs.getString("execution_mode"),
                rs.getString("mandate_status"),
                simulationTradeDate,
                localDate(rs, "contract_start_date"),
                localDate(rs, "contract_end_date"),
                localDateTime(rs, "next_quote_at"),
                rs.getLong("mandate_policy_version"),
                roleEligibilityIssue == null,
                roleEligibilityIssue,
                account,
                policy,
                mapDailyState(rs),
                mapTransition(rs)
        );
    }

    private LiquidityProviderMandateResponse.Transition mapTransition(ResultSet rs)
            throws SQLException {
        Long transitionId = nullableLong(rs, "transition_id");
        if (transitionId == null) {
            return null;
        }
        return new LiquidityProviderMandateResponse.Transition(
                transitionId,
                rs.getString("transition_key"),
                rs.getString("transition_stage"),
                rs.getLong("source_account_id"),
                nullableLong(rs, "legacy_account_id"),
                rs.getLong("transition_reference_daily_volume"),
                rs.getLong("seed_inventory_quantity"),
                money(rs.getBigDecimal("seed_cash_amount")),
                rs.getLong("transferred_inventory_quantity"),
                money(rs.getBigDecimal("transferred_cash_amount")),
                localDate(rs, "transition_effective_business_date"),
                localDateTime(rs, "legacy_disabled_at"),
                localDateTime(rs, "legacy_retired_at"),
                localDateTime(rs, "activated_at"),
                rs.getString("requested_by"),
                rs.getString("change_reason"),
                rs.getLong("transition_policy_version"),
                localDateTime(rs, "transition_created_at"),
                localDateTime(rs, "transition_updated_at")
        );
    }

    private LiquidityProviderMandateResponse.DailyState mapDailyState(ResultSet rs)
            throws SQLException {
        LocalDate tradeDate = localDate(rs, "state_trade_date");
        if (tradeDate == null) {
            return null;
        }
        return new LiquidityProviderMandateResponse.DailyState(
                tradeDate,
                rs.getLong("state_reference_daily_volume"),
                rs.getLong("execution_quantity_limit"),
                rs.getLong("submission_quantity_limit"),
                rs.getLong("submitted_buy_quantity"),
                rs.getLong("submitted_sell_quantity"),
                money(rs.getBigDecimal("submitted_buy_amount")),
                money(rs.getBigDecimal("submitted_sell_amount")),
                rs.getLong("cancelled_buy_quantity"),
                rs.getLong("cancelled_sell_quantity"),
                rs.getLong("executed_buy_quantity"),
                rs.getLong("executed_sell_quantity"),
                money(rs.getBigDecimal("executed_buy_amount")),
                money(rs.getBigDecimal("executed_sell_amount")),
                money(rs.getBigDecimal("realized_profit")),
                money(rs.getBigDecimal("unrealized_profit")),
                money(rs.getBigDecimal("opening_net_asset_value")),
                money(rs.getBigDecimal("current_net_asset_value")),
                money(rs.getBigDecimal("risk_profit")),
                rs.getLong("target_buy_open_quantity"),
                rs.getLong("target_sell_open_quantity"),
                rs.getLong("last_open_buy_quantity"),
                rs.getLong("last_open_sell_quantity"),
                rs.getLong("external_buy_depth_quantity"),
                rs.getLong("external_sell_depth_quantity"),
                nullableMoney(rs.getBigDecimal("last_bid_price")),
                nullableMoney(rs.getBigDecimal("last_ask_price")),
                rs.getLong("last_inventory_quantity"),
                rs.getLong("last_projected_inventory_quantity"),
                rate(rs.getBigDecimal("blended_price_pressure")),
                rate(rs.getBigDecimal("blended_volatility_pressure")),
                rate(rs.getBigDecimal("blended_liquidity_pressure")),
                rs.getString("state_status"),
                rs.getString("gate_reason"),
                rs.getLong("quote_run_count"),
                rs.getBoolean("limit_breached"),
                rs.getLong("state_policy_version"),
                rs.getLong("state_version"),
                localDateTime(rs, "state_updated_at")
        );
    }

    private String roleEligibilityIssue(
            ResultSet rs,
            LocalDate tradeDate,
            long holdingQuantity,
            long reservedSellQuantity,
            long nonLiquidityOpenOrderCount,
            long unmanagedHoldingCount
    ) throws SQLException {
        if (!"ACTIVE".equals(rs.getString("account_status"))
                || !"LIQUIDITY_PROVIDER".equals(rs.getString("participant_category"))) {
            return "ACCOUNT_NOT_ELIGIBLE";
        }
        if (!"ACTIVE".equals(rs.getString("participant_status"))
                || !"LIQUIDITY_PROVIDER".equals(rs.getString("participant_type"))) {
            return "PARTICIPANT_NOT_ELIGIBLE";
        }
        LocalDate effectiveFrom = localDate(rs, "role_effective_from");
        LocalDate effectiveTo = localDate(rs, "role_effective_to");
        if (!"ACTIVE".equals(rs.getString("role_mapping_status"))
                || !"LIQUIDITY_PROVIDER".equals(rs.getString("account_role"))
                || effectiveFrom == null
                || tradeDate.isBefore(effectiveFrom)
                || (effectiveTo != null && tradeDate.isAfter(effectiveTo))) {
            return "ROLE_MAPPING_NOT_EFFECTIVE";
        }
        String accountGroup = rs.getString("account_self_trade_group_id");
        String participantGroup = rs.getString("participant_self_trade_group_id");
        if (accountGroup == null
                || accountGroup.isBlank()
                || !accountGroup.equals(participantGroup)) {
            return "SELF_TRADE_GROUP_MISMATCH";
        }
        if (holdingQuantity < 0
                || reservedSellQuantity < 0
                || reservedSellQuantity > holdingQuantity) {
            return "INVALID_INVENTORY_STATE";
        }
        BigDecimal cashBalance = rs.getBigDecimal("cash_balance");
        if (cashBalance == null || cashBalance.signum() < 0) {
            return "INVALID_CASH_STATE";
        }
        if (nonLiquidityOpenOrderCount > 0) {
            return "NON_LP_OPEN_ORDER_ON_DEDICATED_ACCOUNT";
        }
        if (unmanagedHoldingCount > 0) {
            return "UNMANAGED_HOLDING_ON_DEDICATED_ACCOUNT";
        }
        return null;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? ZERO_MONEY : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(6) : value.setScale(6, RoundingMode.HALF_UP);
    }

    private LocalDate localDate(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
