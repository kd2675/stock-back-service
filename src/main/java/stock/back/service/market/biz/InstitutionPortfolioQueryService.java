package stock.back.service.market.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.InstitutionPortfolioResponse;
import stock.back.service.market.vo.InstitutionPortfolioScheduledPolicyResponse;
import stock.back.service.market.vo.InstitutionSymbolMandateResponse;
import stock.back.service.market.vo.InstitutionSymbolPolicyResponse;

@Service
public class InstitutionPortfolioQueryService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationClockService simulationClockService;

    public InstitutionPortfolioQueryService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true)
    public List<InstitutionPortfolioResponse> getPortfolios() {
        LocalDate simulationTradeDate = simulationClockService.currentSnapshot().simulationDate();
        List<PortfolioHeader> headers = queryHeaders(simulationTradeDate);
        if (headers.isEmpty()) {
            return List.of();
        }
        Map<Long, List<InstitutionSymbolMandateResponse>> mandatesByPortfolioId =
                queryMandates(simulationTradeDate);
        Map<String, InstitutionPortfolioScheduledPolicyResponse> scheduledPolicies =
                queryScheduledPolicies();
        return headers.stream()
                .map(header -> {
                    List<InstitutionSymbolMandateResponse> mandates =
                            mandatesByPortfolioId.getOrDefault(header.portfolioId(), List.of());
                    return header.toResponse(
                            simulationTradeDate,
                            scheduledPolicies.get(header.portfolioCode()),
                            mandates
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public InstitutionPortfolioResponse getPortfolio(long portfolioId) {
        if (portfolioId <= 0L) {
            throw StockException.badRequest("Institution portfolio id must be positive");
        }
        return getPortfolios().stream()
                .filter(portfolio -> portfolio.portfolioId() == portfolioId)
                .findFirst()
                .orElseThrow(() -> StockException.notFound(
                        "Unknown institution portfolio: " + portfolioId
                ));
    }

    private List<PortfolioHeader> queryHeaders(LocalDate simulationTradeDate) {
        return jdbcClient.sql(
                        """
                        select portfolio.id as portfolio_id,
                               portfolio.portfolio_code,
                               portfolio.display_name,
                               portfolio.investment_style,
                               portfolio.execution_mode,
                               portfolio.status as portfolio_status,
                               portfolio.policy_version,
                               portfolio.participant_id,
                               participant.participant_code,
                               participant.status as participant_status,
                               participant.self_trade_group_id as participant_self_trade_group_id,
                               portfolio.account_id,
                               account.user_key as account_user_key,
                               account.status as account_status,
                               account.self_trade_group_id as account_self_trade_group_id,
                               account.cash_balance,
                               portfolio.base_stock_allocation_rate,
                               portfolio.min_stock_allocation_rate,
                               portfolio.max_stock_allocation_rate,
                               portfolio.primary_regime_weight,
                               portfolio.asset_preference_sensitivity,
                               portfolio.volatility_sensitivity,
                               portfolio.entry_threshold_rate,
                               portfolio.exit_threshold_rate,
                               portfolio.daily_turnover_limit_rate,
                               portfolio.max_decision_turnover_rate,
                               portfolio.decision_interval_minutes,
                               portfolio.next_decision_at,
                               coalesce(holding_summary.holding_market_value, 0) as holding_market_value,
                               coalesce(order_summary.open_buy_reserved_cash, 0) as open_buy_reserved_cash,
                               coalesce(order_summary.institutional_open_order_count, 0)
                                   as institutional_open_order_count,
                               (
                                   select count(distinct decision_run.simulation_trade_date)
                                     from stock_institution_decision_run decision_run
                                    where decision_run.portfolio_id = portfolio.id
                                      and decision_run.execution_mode = 'LIVE'
                                      and decision_run.status = 'COMPLETED'
                                      and decision_run.simulation_trade_date < :simulationTradeDate
                               ) as completed_decision_trading_days,
                               (
                                   select count(*)
                                     from stock_institution_decision_run failed_run
                                    where failed_run.portfolio_id = portfolio.id
                                      and failed_run.execution_mode = 'LIVE'
                                      and failed_run.status = 'FAILED'
                                      and failed_run.simulation_trade_date >= :decisionFailureFromDate
                                      and failed_run.simulation_trade_date < :simulationTradeDate
                               ) as recent_decision_failure_count,
                               latest_run.id as latest_decision_run_id,
                               latest_run.decision_slot as latest_decision_slot,
                               latest_run.status as latest_decision_status,
                               latest_run.deterministic_seed as latest_deterministic_seed,
                               latest_run.error_message as latest_decision_error,
                               latest_run.completed_at as latest_decision_completed_at,
                               coalesce(budget_summary.planned_buy_quantity, 0)
                                   as daily_planned_buy_quantity,
                               coalesce(budget_summary.planned_sell_quantity, 0)
                                   as daily_planned_sell_quantity,
                               coalesce(budget_summary.planned_buy_amount, 0)
                                   as daily_planned_buy_amount,
                               coalesce(budget_summary.planned_sell_amount, 0)
                                   as daily_planned_sell_amount,
                               coalesce(budget_summary.submitted_buy_amount, 0)
                                   as daily_submitted_buy_amount,
                               coalesce(budget_summary.submitted_sell_amount, 0)
                                   as daily_submitted_sell_amount
                          from stock_institution_portfolio portfolio
                          join stock_market_participant participant
                            on participant.id = portfolio.participant_id
                          join stock_account account
                            on account.id = portfolio.account_id
                          left join (
                              select holding.account_id,
                                     sum(holding.quantity * price.current_price)
                                         as holding_market_value
                                from stock_holding holding
                                join stock_price price on price.symbol = holding.symbol
                               group by holding.account_id
                          ) holding_summary on holding_summary.account_id = portfolio.account_id
                          left join (
                              select stock_order.account_id,
                                     sum(
                                         case
                                           when stock_order.side = 'BUY'
                                           then stock_order.reserved_cash
                                           else 0
                                         end
                                     ) as open_buy_reserved_cash,
                                     sum(
                                         case
                                           when stock_order.origin_type = 'INSTITUTIONAL_INVESTOR'
                                           then 1
                                           else 0
                                         end
                                     ) as institutional_open_order_count
                                from stock_order
                               where stock_order.market_type = 'ORDER_BOOK'
                                 and stock_order.status in ('PENDING', 'PARTIALLY_FILLED')
                               group by stock_order.account_id
                          ) order_summary on order_summary.account_id = portfolio.account_id
                          left join stock_institution_decision_run latest_run
                            on latest_run.id = (
                                select candidate.id
                                  from stock_institution_decision_run candidate
                                 where candidate.portfolio_id = portfolio.id
                                 order by candidate.decision_slot desc, candidate.id desc
                                 limit 1
                            )
                          left join (
                              select budget.portfolio_id,
                                     sum(budget.planned_buy_quantity) as planned_buy_quantity,
                                     sum(budget.planned_sell_quantity) as planned_sell_quantity,
                                     sum(budget.planned_buy_amount) as planned_buy_amount,
                                     sum(budget.planned_sell_amount) as planned_sell_amount,
                                     sum(budget.submitted_buy_amount) as submitted_buy_amount,
                                     sum(budget.submitted_sell_amount) as submitted_sell_amount
                                from stock_institution_daily_budget budget
                               where budget.simulation_trade_date = :simulationTradeDate
                               group by budget.portfolio_id
                          ) budget_summary on budget_summary.portfolio_id = portfolio.id
                         order by portfolio.display_name asc, portfolio.id asc
                        """
                )
                .param("simulationTradeDate", simulationTradeDate)
                .param("decisionFailureFromDate", simulationTradeDate.minusDays(20))
                .query((rs, rowNum) -> mapHeader(rs))
                .list();
    }

    private Map<Long, List<InstitutionSymbolMandateResponse>> queryMandates(
            LocalDate simulationTradeDate
    ) {
        List<MandateRow> rows = jdbcClient.sql(
                        """
                        select mandate.id as mandate_id,
                               mandate.portfolio_id,
                               mandate.symbol,
                               mandate.base_symbol_weight,
                               mandate.min_portfolio_allocation_rate,
                               mandate.max_portfolio_allocation_rate,
                               mandate.price_pressure_sensitivity,
                               mandate.momentum_sensitivity,
                               mandate.value_sensitivity,
                               mandate.report_sensitivity,
                               mandate.reference_daily_volume,
                               mandate.daily_participation_rate,
                               mandate.enabled,
                               portfolio.execution_mode,
                               coalesce(price.current_price, 0) as current_price,
                               coalesce(holding.quantity, 0) as actual_quantity,
                               coalesce(holding.reserved_quantity, 0) as reserved_quantity,
                               coalesce(open_order.open_buy_quantity, 0) as open_buy_quantity,
                               coalesce(open_order.open_sell_quantity, 0) as open_sell_quantity,
                               decision_item.actual_allocation_rate,
                               decision_item.projected_allocation_rate,
                               decision_item.target_allocation_rate,
                               decision_item.action,
                               decision_item.decision_reason,
                               decision_item.gate_reason,
                               decision_item.gated_quantity,
                               decision_item.gated_trade_amount,
                               decision_item.blended_price_pressure,
                               decision_item.blended_asset_preference_pressure,
                               decision_item.blended_volatility_pressure,
                               decision_item.blended_liquidity_pressure,
                               decision_item.blended_execution_aggression_pressure,
                               decision_item.return_5_day,
                               decision_item.return_20_day,
                               decision_item.report_pressure,
                               coalesce(daily_budget.gross_quantity_limit, 0)
                                   as daily_gross_quantity_limit,
                               coalesce(daily_budget.planned_buy_quantity, 0)
                                   as daily_planned_buy_quantity,
                               coalesce(daily_budget.planned_sell_quantity, 0)
                                   as daily_planned_sell_quantity,
                               coalesce(daily_budget.gross_notional_limit, 0)
                                   as daily_gross_notional_limit,
                               coalesce(daily_budget.planned_buy_amount, 0)
                                   as daily_planned_buy_amount,
                               coalesce(daily_budget.planned_sell_amount, 0)
                                   as daily_planned_sell_amount,
                               coalesce(daily_budget.submitted_buy_amount, 0)
                                   as daily_submitted_buy_amount,
                               coalesce(daily_budget.submitted_sell_amount, 0)
                                   as daily_submitted_sell_amount,
                               order_intent.status as order_intent_status,
                               coalesce(order_intent.attempt_count, 0)
                                   as order_intent_attempt_count,
                               coalesce(order_intent.requested_quantity, 0)
                                   as order_intent_requested_quantity,
                               coalesce(order_intent.planned_amount, 0)
                                   as order_intent_planned_amount,
                               order_intent.submitted_order_id,
                               order_intent.submitted_price,
                               coalesce(order_intent.submitted_quantity, 0)
                                   as submitted_quantity,
                               order_intent.submission_reason,
                               order_intent.submitted_at
                          from stock_institution_symbol_mandate mandate
                          join stock_institution_portfolio portfolio
                            on portfolio.id = mandate.portfolio_id
                          left join stock_price price on price.symbol = mandate.symbol
                          left join stock_holding holding
                            on holding.account_id = portfolio.account_id
                           and holding.symbol = mandate.symbol
                          left join (
                              select stock_order.account_id,
                                     stock_order.symbol,
                                     sum(
                                         case
                                           when stock_order.side = 'BUY'
                                           then stock_order.quantity - stock_order.filled_quantity
                                           else 0
                                         end
                                     ) as open_buy_quantity,
                                     sum(
                                         case
                                           when stock_order.side = 'SELL'
                                           then stock_order.quantity - stock_order.filled_quantity
                                           else 0
                                         end
                                     ) as open_sell_quantity
                                from stock_order
                               where stock_order.market_type = 'ORDER_BOOK'
                                 and stock_order.status in ('PENDING', 'PARTIALLY_FILLED')
                               group by stock_order.account_id, stock_order.symbol
                          ) open_order
                            on open_order.account_id = portfolio.account_id
                           and open_order.symbol = mandate.symbol
                          left join stock_institution_decision_item decision_item
                            on decision_item.symbol = mandate.symbol
                           and decision_item.decision_run_id = (
                               select completed_run.id
                                 from stock_institution_decision_run completed_run
                                where completed_run.portfolio_id = portfolio.id
                                  and completed_run.status = 'COMPLETED'
                                order by completed_run.decision_slot desc, completed_run.id desc
                                limit 1
                           )
                          left join stock_institution_order_intent order_intent
                            on order_intent.decision_run_id = decision_item.decision_run_id
                           and order_intent.symbol = decision_item.symbol
                          left join stock_institution_daily_budget daily_budget
                            on daily_budget.portfolio_id = portfolio.id
                           and daily_budget.symbol = mandate.symbol
                           and daily_budget.simulation_trade_date = :simulationTradeDate
                         order by portfolio.display_name asc, portfolio.id asc, mandate.symbol asc
                        """
                )
                .param("simulationTradeDate", simulationTradeDate)
                .query((rs, rowNum) -> mapMandate(rs))
                .list();
        Map<Long, List<InstitutionSymbolMandateResponse>> result = new LinkedHashMap<>();
        for (MandateRow row : rows) {
            result.computeIfAbsent(row.portfolioId(), ignored -> new ArrayList<>())
                    .add(row.response());
        }
        return result;
    }

    private Map<String, InstitutionPortfolioScheduledPolicyResponse> queryScheduledPolicies() {
        Map<String, InstitutionPortfolioScheduledPolicyResponse> result = new LinkedHashMap<>();
        List<ScheduledPolicyRow> rows = jdbcClient.sql(
                        """
                        select scope_key, version_no, effective_business_date, config_json,
                               change_reason, changed_by, updated_at
                          from stock_market_policy_version
                         where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                           and status = 'SCHEDULED'
                         order by scope_key asc, version_no asc
                        """
                )
                .query((rs, rowNum) -> new ScheduledPolicyRow(
                        rs.getString("scope_key"),
                        rs.getLong("version_no"),
                        rs.getObject("effective_business_date", LocalDate.class),
                        rs.getString("config_json"),
                        rs.getString("change_reason"),
                        rs.getString("changed_by"),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .list();
        for (ScheduledPolicyRow row : rows) {
            InstitutionPortfolioScheduledPolicyResponse response = parseScheduledPolicy(row);
            if (response == null) {
                continue;
            }
            InstitutionPortfolioScheduledPolicyResponse previous =
                    result.putIfAbsent(row.portfolioCode(), response);
            if (previous != null) {
                throw new IllegalStateException(
                        "Institution portfolio has multiple scheduled policies: "
                                + row.portfolioCode()
                );
            }
        }
        return Map.copyOf(result);
    }

    private InstitutionPortfolioScheduledPolicyResponse parseScheduledPolicy(
            ScheduledPolicyRow row
    ) {
        JsonNode root;
        try {
            root = objectMapper.readTree(row.configJson());
            if (root != null && root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Scheduled institution policy JSON is invalid: " + row.portfolioCode(),
                    ex
            );
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException(
                    "Scheduled institution policy JSON must be an object: "
                            + row.portfolioCode()
            );
        }
        if (!"INDEPENDENT_INSTITUTION_PORTFOLIO_V2".equals(text(root, "preset"))) {
            return null;
        }
        JsonNode mandateNodes = requiredNode(root, "mandates");
        if (!mandateNodes.isArray() || mandateNodes.isEmpty()) {
            throw new IllegalStateException(
                    "Scheduled institution policy mandates must be a non-empty array: "
                            + row.portfolioCode()
            );
        }
        List<InstitutionSymbolPolicyResponse> mandates = new ArrayList<>();
        for (JsonNode mandate : mandateNodes) {
            mandates.add(new InstitutionSymbolPolicyResponse(
                    text(mandate, "symbol"),
                    decimal(mandate, "baseSymbolWeight"),
                    decimal(mandate, "minPortfolioAllocationRate"),
                    decimal(mandate, "maxPortfolioAllocationRate"),
                    decimal(mandate, "pricePressureSensitivity"),
                    decimal(mandate, "momentumSensitivity"),
                    decimal(mandate, "valueSensitivity"),
                    decimal(mandate, "reportSensitivity"),
                    longValue(mandate, "referenceDailyVolume"),
                    decimal(mandate, "dailyParticipationRate")
            ));
        }
        return new InstitutionPortfolioScheduledPolicyResponse(
                row.version(),
                row.effectiveBusinessDate(),
                text(root, "displayName"),
                text(root, "investmentStyle"),
                decimal(root, "baseStockAllocationRate"),
                decimal(root, "minStockAllocationRate"),
                decimal(root, "maxStockAllocationRate"),
                decimal(root, "primaryRegimeWeight"),
                decimal(root, "assetPreferenceSensitivity"),
                decimal(root, "volatilitySensitivity"),
                decimal(root, "entryThresholdRate"),
                decimal(root, "exitThresholdRate"),
                decimal(root, "dailyTurnoverLimitRate"),
                decimal(root, "maxDecisionTurnoverRate"),
                integer(root, "decisionIntervalMinutes"),
                mandates,
                row.changeReason(),
                row.changedBy(),
                row.updatedAt()
        );
    }

    private JsonNode requiredNode(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field is missing: " + fieldName
            );
        }
        return value;
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode value = requiredNode(root, fieldName);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field must be non-blank text: " + fieldName
            );
        }
        return value.textValue();
    }

    private BigDecimal decimal(JsonNode root, String fieldName) {
        JsonNode value = requiredNode(root, fieldName);
        if (!value.isNumber()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field must be numeric: " + fieldName
            );
        }
        return value.decimalValue();
    }

    private long longValue(JsonNode root, String fieldName) {
        JsonNode value = requiredNode(root, fieldName);
        if (!value.canConvertToLong()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field must be a long: " + fieldName
            );
        }
        return value.longValue();
    }

    private int integer(JsonNode root, String fieldName) {
        JsonNode value = requiredNode(root, fieldName);
        if (!value.canConvertToInt()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field must be an integer: " + fieldName
            );
        }
        return value.intValue();
    }

    private PortfolioHeader mapHeader(ResultSet rs) throws SQLException {
        return new PortfolioHeader(
                rs.getLong("portfolio_id"),
                rs.getString("portfolio_code"),
                rs.getString("display_name"),
                rs.getString("investment_style"),
                rs.getString("execution_mode"),
                rs.getString("portfolio_status"),
                rs.getLong("policy_version"),
                rs.getLong("participant_id"),
                rs.getString("participant_code"),
                rs.getString("participant_status"),
                rs.getString("participant_self_trade_group_id"),
                rs.getLong("account_id"),
                rs.getString("account_user_key"),
                rs.getString("account_status"),
                rs.getString("account_self_trade_group_id"),
                zero(rs.getBigDecimal("cash_balance")),
                zero(rs.getBigDecimal("open_buy_reserved_cash")),
                zero(rs.getBigDecimal("holding_market_value")),
                rs.getBigDecimal("base_stock_allocation_rate"),
                rs.getBigDecimal("min_stock_allocation_rate"),
                rs.getBigDecimal("max_stock_allocation_rate"),
                rs.getBigDecimal("primary_regime_weight"),
                rs.getBigDecimal("asset_preference_sensitivity"),
                rs.getBigDecimal("volatility_sensitivity"),
                rs.getBigDecimal("entry_threshold_rate"),
                rs.getBigDecimal("exit_threshold_rate"),
                rs.getBigDecimal("daily_turnover_limit_rate"),
                rs.getBigDecimal("max_decision_turnover_rate"),
                rs.getInt("decision_interval_minutes"),
                rs.getObject("next_decision_at", LocalDateTime.class),
                nullableLong(rs, "latest_decision_run_id"),
                rs.getObject("latest_decision_slot", LocalDateTime.class),
                rs.getString("latest_decision_status"),
                nullableLong(rs, "latest_deterministic_seed"),
                rs.getString("latest_decision_error"),
                rs.getObject("latest_decision_completed_at", LocalDateTime.class),
                rs.getLong("daily_planned_buy_quantity"),
                rs.getLong("daily_planned_sell_quantity"),
                zero(rs.getBigDecimal("daily_planned_buy_amount")),
                zero(rs.getBigDecimal("daily_planned_sell_amount")),
                zero(rs.getBigDecimal("daily_submitted_buy_amount")),
                zero(rs.getBigDecimal("daily_submitted_sell_amount")),
                rs.getLong("institutional_open_order_count"),
                rs.getInt("completed_decision_trading_days"),
                rs.getInt("recent_decision_failure_count")
        );
    }

    private MandateRow mapMandate(ResultSet rs) throws SQLException {
        long actualQuantity = rs.getLong("actual_quantity");
        long openBuyQuantity = rs.getLong("open_buy_quantity");
        long openSellQuantity = rs.getLong("open_sell_quantity");
        long plannedBuyQuantity = rs.getLong("daily_planned_buy_quantity");
        long plannedSellQuantity = rs.getLong("daily_planned_sell_quantity");
        long projectedQuantity = projectedQuantity(
                actualQuantity,
                openBuyQuantity,
                openSellQuantity
        );
        return new MandateRow(
                rs.getLong("portfolio_id"),
                new InstitutionSymbolMandateResponse(
                        rs.getLong("mandate_id"),
                        rs.getString("symbol"),
                        rs.getBigDecimal("base_symbol_weight"),
                        rs.getBigDecimal("min_portfolio_allocation_rate"),
                        rs.getBigDecimal("max_portfolio_allocation_rate"),
                        rs.getBigDecimal("price_pressure_sensitivity"),
                        rs.getBigDecimal("momentum_sensitivity"),
                        rs.getBigDecimal("value_sensitivity"),
                        rs.getBigDecimal("report_sensitivity"),
                        rs.getLong("reference_daily_volume"),
                        rs.getBigDecimal("daily_participation_rate"),
                        rs.getBoolean("enabled"),
                        zero(rs.getBigDecimal("current_price")),
                        actualQuantity,
                        rs.getLong("reserved_quantity"),
                        openBuyQuantity,
                        openSellQuantity,
                        projectedQuantity,
                        rs.getBigDecimal("actual_allocation_rate"),
                        rs.getBigDecimal("projected_allocation_rate"),
                        rs.getBigDecimal("target_allocation_rate"),
                        rs.getString("action"),
                        rs.getString("decision_reason"),
                        rs.getString("gate_reason"),
                        rs.getLong("gated_quantity"),
                        rs.getBigDecimal("gated_trade_amount"),
                        rs.getBigDecimal("blended_price_pressure"),
                        rs.getBigDecimal("blended_asset_preference_pressure"),
                        rs.getBigDecimal("blended_volatility_pressure"),
                        rs.getBigDecimal("blended_liquidity_pressure"),
                        rs.getBigDecimal("blended_execution_aggression_pressure"),
                        rs.getBigDecimal("return_5_day"),
                        rs.getBigDecimal("return_20_day"),
                        rs.getBigDecimal("report_pressure"),
                        rs.getLong("daily_gross_quantity_limit"),
                        plannedBuyQuantity,
                        plannedSellQuantity,
                        zero(rs.getBigDecimal("daily_gross_notional_limit")),
                        zero(rs.getBigDecimal("daily_planned_buy_amount")),
                        zero(rs.getBigDecimal("daily_planned_sell_amount")),
                        zero(rs.getBigDecimal("daily_submitted_buy_amount")),
                        zero(rs.getBigDecimal("daily_submitted_sell_amount")),
                        rs.getString("order_intent_status"),
                        rs.getInt("order_intent_attempt_count"),
                        rs.getLong("order_intent_requested_quantity"),
                        zero(rs.getBigDecimal("order_intent_planned_amount")),
                        nullableLong(rs, "submitted_order_id"),
                        rs.getBigDecimal("submitted_price"),
                        rs.getLong("submitted_quantity"),
                        rs.getString("submission_reason"),
                        rs.getObject("submitted_at", LocalDateTime.class)
                )
        );
    }

    private long projectedQuantity(
            long actual,
            long openBuy,
            long openSell
    ) {
        long afterBuy = openBuy > Long.MAX_VALUE - actual ? Long.MAX_VALUE : actual + openBuy;
        return Math.max(0L, afterBuy - Math.min(afterBuy, openSell));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record PortfolioHeader(
            long portfolioId,
            String portfolioCode,
            String displayName,
            String investmentStyle,
            String executionMode,
            String status,
            long policyVersion,
            long participantId,
            String participantCode,
            String participantStatus,
            String participantSelfTradeGroupId,
            long accountId,
            String accountUserKey,
            String accountStatus,
            String accountSelfTradeGroupId,
            BigDecimal cashBalance,
            BigDecimal openBuyReservedCash,
            BigDecimal holdingMarketValue,
            BigDecimal baseStockAllocationRate,
            BigDecimal minStockAllocationRate,
            BigDecimal maxStockAllocationRate,
            BigDecimal primaryRegimeWeight,
            BigDecimal assetPreferenceSensitivity,
            BigDecimal volatilitySensitivity,
            BigDecimal entryThresholdRate,
            BigDecimal exitThresholdRate,
            BigDecimal dailyTurnoverLimitRate,
            BigDecimal maxDecisionTurnoverRate,
            int decisionIntervalMinutes,
            LocalDateTime nextDecisionAt,
            Long latestDecisionRunId,
            LocalDateTime latestDecisionSlot,
            String latestDecisionStatus,
            Long latestDeterministicSeed,
            String latestDecisionError,
            LocalDateTime latestDecisionCompletedAt,
            long dailyPlannedBuyQuantity,
            long dailyPlannedSellQuantity,
            BigDecimal dailyPlannedBuyAmount,
            BigDecimal dailyPlannedSellAmount,
            BigDecimal dailySubmittedBuyAmount,
            BigDecimal dailySubmittedSellAmount,
            long institutionalOpenOrderCount,
            int completedDecisionTradingDays,
            int recentDecisionFailureCount
    ) {

        InstitutionPortfolioResponse toResponse(
                LocalDate budgetTradeDate,
                InstitutionPortfolioScheduledPolicyResponse scheduledPolicy,
                List<InstitutionSymbolMandateResponse> mandates
        ) {
            BigDecimal totalAsset = cashBalance.add(openBuyReservedCash).add(holdingMarketValue);
            BigDecimal currentStockAllocationRate = totalAsset.signum() <= 0
                    ? BigDecimal.ZERO.setScale(8)
                    : holdingMarketValue.divide(totalAsset, 8, RoundingMode.HALF_UP);
            return new InstitutionPortfolioResponse(
                    portfolioId,
                    portfolioCode,
                    displayName,
                    investmentStyle,
                    executionMode,
                    status,
                    policyVersion,
                    participantId,
                    participantCode,
                    participantStatus,
                    participantSelfTradeGroupId,
                    accountId,
                    accountUserKey,
                    accountStatus,
                    accountSelfTradeGroupId,
                    cashBalance,
                    openBuyReservedCash,
                    holdingMarketValue,
                    totalAsset,
                    currentStockAllocationRate,
                    baseStockAllocationRate,
                    minStockAllocationRate,
                    maxStockAllocationRate,
                    primaryRegimeWeight,
                    assetPreferenceSensitivity,
                    volatilitySensitivity,
                    entryThresholdRate,
                    exitThresholdRate,
                    dailyTurnoverLimitRate,
                    maxDecisionTurnoverRate,
                    decisionIntervalMinutes,
                    nextDecisionAt,
                    latestDecisionRunId,
                    latestDecisionSlot,
                    latestDecisionStatus,
                    latestDeterministicSeed,
                    latestDecisionError,
                    latestDecisionCompletedAt,
                    budgetTradeDate,
                    dailyPlannedBuyQuantity,
                    dailyPlannedSellQuantity,
                    dailyPlannedBuyAmount,
                    dailyPlannedSellAmount,
                    dailySubmittedBuyAmount,
                    dailySubmittedSellAmount,
                    institutionalOpenOrderCount,
                    completedDecisionTradingDays,
                    recentDecisionFailureCount,
                    scheduledPolicy,
                    mandates
            );
        }
    }

    private record ScheduledPolicyRow(
            String portfolioCode,
            long version,
            LocalDate effectiveBusinessDate,
            String configJson,
            String changeReason,
            String changedBy,
            LocalDateTime updatedAt
    ) {
    }

    private record MandateRow(long portfolioId, InstitutionSymbolMandateResponse response) {
    }
}
