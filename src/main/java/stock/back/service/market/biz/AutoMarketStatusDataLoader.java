package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.market.vo.AutoMarketDailyRegimeResponse;
import stock.back.service.market.vo.AutoMarketRegimeModifierResponse;
import stock.back.service.market.vo.AutoParticipantLifecycleScope;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AutoMarketStatusDataLoader {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository;

    public AutoMarketStatusDataLoader(
            JdbcTemplate jdbcTemplate,
            StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.stockAutoParticipantSymbolConfigRepository = stockAutoParticipantSymbolConfigRepository;
    }

    List<AutoParticipantResponse> loadAutoParticipantStatusResponses() {
        return loadAutoParticipantStatusResponses(AutoParticipantLifecycleScope.CURRENT);
    }

    List<AutoParticipantResponse> loadAutoParticipantStatusResponses(AutoParticipantLifecycleScope lifecycleScope) {
        AutoParticipantLifecycleScope effectiveScope = AutoParticipantLifecycleScope.effective(lifecycleScope);
        String lifecyclePredicate = effectiveScope == AutoParticipantLifecycleScope.WITHDRAWN
                ? "is not null"
                : "is null";
        String participantOrder = effectiveScope == AutoParticipantLifecycleScope.WITHDRAWN
                ? "p.withdrawn_at desc, p.user_key asc"
                : "p.user_key asc";
        String sql = """
                select p.user_key,
                       p.display_name,
                       p.enabled,
                       p.profile_type,
                       coalesce(pc.behavior_model_version, 'V2') as behavior_model_version,
                       p.behavior_seed,
                       p.recurring_cash_amount,
                       p.recurring_cash_interval_value,
                       p.recurring_cash_interval_unit,
                       p.created_at,
                       p.updated_at,
                       p.withdrawn_at,
                       a.id as account_id,
                       a.status as account_status,
                       a.cash_balance,
                       coalesce(b.payday_available_budget, 0) as payday_available_budget,
                       coalesce(b.dividend_available_budget, 0) as dividend_available_budget,
                       coalesce(b.funding_reserved_amount, 0) as funding_reserved_amount,
                       coalesce(b.funding_spent_amount, 0) as funding_spent_amount,
                       coalesce(b.active_funding_budget_count, 0) as active_funding_budget_count,
                       coalesce(s.tracked_position_count, 0) as tracked_position_count,
                       coalesce(s.average_holding_trading_days, 0) as average_holding_trading_days,
                       coalesce(s.average_down_round_count, 0) as average_down_round_count,
                       coalesce(w.returned_cash_amount, 0) as withdrawal_returned_cash_amount,
                       coalesce(w.returned_share_quantity, 0) as withdrawal_returned_share_quantity,
                       coalesce(w.returned_symbol_count, 0) as withdrawal_returned_symbol_count,
                       case when w.id is not null and a.status = 'CLOSED' then true else false end
                           as account_closed_on_withdrawal
                  from stock_auto_participant p
                  left join stock_auto_participant_profile_config pc
                    on pc.profile_type = p.profile_type
                  left join stock_account a on a.user_key = p.user_key
                  left join stock_auto_participant_withdrawal w
                    on w.participant_user_key = p.user_key
	              left join (
	                   select account_id,
	                          sum(case
	                                  when budget_type = 'PAYDAY'
	                                   and status = 'ACTIVE'
	                                   and (
	                                        expires_business_date is null
	                                        or expires_business_date >= (
	                                            select active_business_date
	                                              from stock_market_business_state
	                                             where state_id = 'DEFAULT'
	                                        )
	                                   )
	                                  then available_amount
	                                  else 0
	                              end) as payday_available_budget,
	                          sum(case
	                                  when budget_type = 'DIVIDEND'
	                                   and status = 'ACTIVE'
	                                   and (
	                                        expires_business_date is null
	                                        or expires_business_date >= (
	                                            select active_business_date
	                                              from stock_market_business_state
	                                             where state_id = 'DEFAULT'
	                                        )
	                                   )
	                                  then available_amount
	                                  else 0
	                              end) as dividend_available_budget,
	                          sum(reserved_amount) as funding_reserved_amount,
	                          sum(spent_amount) as funding_spent_amount,
	                          sum(case
	                                  when status = 'ACTIVE'
	                                   and (
	                                        expires_business_date is null
	                                        or expires_business_date >= (
	                                            select active_business_date
	                                              from stock_market_business_state
	                                             where state_id = 'DEFAULT'
	                                        )
	                                   )
	                                  then 1
	                                  else 0
	                              end) as active_funding_budget_count
	                     from stock_auto_participant_funding_budget
	                    group by account_id
	              ) b on b.account_id = a.id
	              left join (
	                   select account_id,
	                          count(*) as tracked_position_count,
	                          avg(holding_trading_days) as average_holding_trading_days,
	                          sum(average_down_rounds) as average_down_round_count
	                     from stock_auto_participant_position_state
	                    group by account_id
	              ) s on s.account_id = a.id
	                 where p.withdrawn_at %s
	                 order by %s
	                """.formatted(lifecyclePredicate, participantOrder);
        return jdbcClient.sql(sql)
                .query((rs, rowNum) -> AutoMarketStatusResponseMapper.toParticipant(rs))
                .list();
    }

    List<AutoParticipantSymbolConfigTarget> resolveAutoParticipantSymbolConfigTargets(
            List<AutoParticipantResponse> participants,
            String participantSymbolConfigUserKey
    ) {
        if (participantSymbolConfigUserKey == null) {
            return participants.stream()
                    .map(participant -> new AutoParticipantSymbolConfigTarget(participant.userKey(), participant.updatedAt()))
                    .toList();
        }
        String sql = """
                select p.user_key,
                       p.updated_at
                  from stock_auto_participant p
	                 where p.withdrawn_at is null
	                   and p.user_key = ?
	                """;
        return jdbcClient.sql(sql)
                .param(participantSymbolConfigUserKey)
                .query((rs, rowNum) -> new AutoParticipantSymbolConfigTarget(
                        rs.getString("user_key"),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .list();
    }

    List<AutoParticipantSymbolConfigResponse> loadEffectiveAutoParticipantSymbolConfigs(
            List<AutoParticipantSymbolConfigTarget> participantTargets,
            List<StockAutoMarketConfig> configEntities
    ) {
        if (participantTargets.isEmpty() || configEntities.isEmpty()) {
            return List.of();
        }
        List<String> userKeys = participantTargets.stream()
                .map(AutoParticipantSymbolConfigTarget::userKey)
                .toList();
        Map<String, StockAutoParticipantSymbolConfig> savedParticipantSymbolConfigs = stockAutoParticipantSymbolConfigRepository.findByUserKeyInOrderByUserKeyAscSymbolAsc(userKeys)
                .stream()
                .collect(Collectors.toMap(
                        config -> autoParticipantSymbolConfigKey(config.getUserKey(), config.getSymbol()),
                        Function.identity(),
                        (left, right) -> left
                ));
        return participantTargets.stream()
                .flatMap(participantTarget -> configEntities.stream()
                        .map(config -> AutoMarketStatusResponseMapper.toEffectiveParticipantSymbolConfig(
                                participantTarget.userKey(),
                                participantTarget.updatedAt(),
                                config,
                                savedParticipantSymbolConfigs.get(autoParticipantSymbolConfigKey(participantTarget.userKey(), config.getSymbol()))
                        )))
                .toList();
    }

    Map<String, AutoMarketDailyRegimeResponse> loadDailyRegimesBySymbol(
            List<String> symbols,
            LocalDate simulationTradeDate,
            String regimePhase,
            LocalDateTime currentMarketDateTime
    ) {
        if (symbols.isEmpty() || simulationTradeDate == null || regimePhase == null || regimePhase.isBlank()) {
            return Map.of();
        }
        Map<String, AutoMarketRegimeModifierResponse> modifiersBySymbol = loadCurrentRegimeModifiersBySymbol(
                symbols,
                simulationTradeDate,
                regimePhase,
                modifierWindowStartAt(currentMarketDateTime)
        );
        String placeholders = symbols.stream()
                .map(symbol -> "?")
                .collect(Collectors.joining(", "));
        String sql = """
                select symbol,
                       simulation_trade_date,
                       regime_phase,
                       coalesce(source_regime_phase, regime_phase) as source_regime_phase,
                       (
                           select count(*)
                             from stock_order_book_daily_regime prepared
                            where prepared.symbol = current_regime.symbol
                              and prepared.simulation_trade_date = current_regime.simulation_trade_date
                       ) as prepared_regime_slot_count,
                       (
                           select count(*)
                             from stock_order_book_daily_regime applied
                            where applied.symbol = current_regime.symbol
                              and applied.simulation_trade_date = current_regime.simulation_trade_date
                              and coalesce(applied.source_regime_phase, applied.regime_phase) = applied.regime_phase
                       ) as daily_application_count,
                       price_pressure,
                       asset_preference_pressure,
                       volatility_pressure,
                       liquidity_pressure,
                       execution_aggression_pressure,
                       seed,
                       created_at,
                       updated_at
                 from stock_order_book_daily_regime current_regime
                 where current_regime.symbol in (%s)
                   and current_regime.simulation_trade_date = ?
                   and current_regime.regime_phase = ?
                 order by current_regime.symbol asc
                """.formatted(placeholders);
        List<Object> params = new ArrayList<>(symbols);
        params.add(simulationTradeDate);
        params.add(regimePhase);
        List<AutoMarketDailyRegimeResponse> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new AutoMarketDailyRegimeResponse(
                        rs.getString("symbol"),
                        rs.getObject("simulation_trade_date", LocalDate.class),
                        rs.getString("regime_phase"),
                        rs.getString("source_regime_phase"),
                        rs.getInt("daily_application_count"),
                        rs.getInt("prepared_regime_slot_count"),
                        rs.getInt("price_pressure"),
                        rs.getInt("asset_preference_pressure"),
                        rs.getInt("volatility_pressure"),
                        rs.getInt("liquidity_pressure"),
                        rs.getInt("execution_aggression_pressure"),
                        Long.toString(rs.getLong("seed")),
                        modifiersBySymbol.get(rs.getString("symbol")),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)
                ),
                params.toArray()
        );
        Map<String, AutoMarketDailyRegimeResponse> regimesBySymbol = new LinkedHashMap<>();
        for (AutoMarketDailyRegimeResponse row : rows) {
            regimesBySymbol.put(row.symbol(), row);
        }
        return regimesBySymbol;
    }

    private Map<String, AutoMarketRegimeModifierResponse> loadCurrentRegimeModifiersBySymbol(
            List<String> symbols,
            LocalDate simulationTradeDate,
            String regimePhase,
            LocalDateTime modifierWindowStartAt
    ) {
        if (symbols.isEmpty() || modifierWindowStartAt == null) {
            return Map.of();
        }
        String placeholders = symbols.stream()
                .map(symbol -> "?")
                .collect(Collectors.joining(", "));
        String sql = """
                select symbol,
                       modifier_window_start_at,
                       price_pressure,
                       asset_preference_pressure,
                       volatility_pressure,
                       liquidity_pressure,
                       execution_aggression_pressure,
                       seed,
                       created_at,
                       updated_at
                 from stock_order_book_regime_modifier
                 where symbol in (%s)
                   and simulation_trade_date = ?
                   and regime_phase = ?
                   and modifier_window_start_at = ?
                 order by symbol asc
                """.formatted(placeholders);
        List<Object> params = new ArrayList<>(symbols);
        params.add(simulationTradeDate);
        params.add(regimePhase);
        params.add(modifierWindowStartAt);
        List<ModifierRow> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ModifierRow(
                        rs.getString("symbol"),
                        new AutoMarketRegimeModifierResponse(
                                rs.getObject("modifier_window_start_at", LocalDateTime.class),
                                rs.getInt("price_pressure"),
                                rs.getInt("asset_preference_pressure"),
                                rs.getInt("volatility_pressure"),
                                rs.getInt("liquidity_pressure"),
                                rs.getInt("execution_aggression_pressure"),
                                Long.toString(rs.getLong("seed")),
                                rs.getObject("created_at", LocalDateTime.class),
                                rs.getObject("updated_at", LocalDateTime.class)
                        )
                ),
                params.toArray()
        );
        Map<String, AutoMarketRegimeModifierResponse> modifiersBySymbol = new LinkedHashMap<>();
        for (ModifierRow row : rows) {
            modifiersBySymbol.put(row.symbol(), row.response());
        }
        return modifiersBySymbol;
    }

    private LocalDateTime modifierWindowStartAt(LocalDateTime now) {
        if (now == null) {
            return null;
        }
        int minute = now.getMinute() < 30 ? 0 : 30;
        return now.withMinute(minute).withSecond(0).withNano(0);
    }

    private record ModifierRow(String symbol, AutoMarketRegimeModifierResponse response) {
    }

    private String autoParticipantSymbolConfigKey(String userKey, String symbol) {
        return userKey + "\n" + symbol;
    }
}
