package stock.back.service.market.biz;

import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketDailyRegimeResponse;
import stock.back.service.market.vo.AutoMarketDistributionBiasResponse;
import stock.back.service.market.vo.AutoMarketRegimeCountWeightsResponse;
import stock.back.service.market.vo.AutoMarketStatusResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

final class AutoMarketStatusResponseMapper {

    private AutoMarketStatusResponseMapper() {
    }

    static AutoMarketStatusResponse toSummaryStatus(ResultSet rs, int profileConfigCount) throws SQLException {
        long enabledParticipantCount = rs.getLong("enabled_participant_count");
        return new AutoMarketStatusResponse(
                enabledParticipantCount > 0 && rs.getLong("enabled_config_count") > 0,
                rs.getLong("config_count"),
                rs.getLong("participant_count"),
                profileConfigCount,
                enabledParticipantCount,
                rs.getLong("salary_eligible_participant_count"),
                rs.getLong("open_auto_order_count"),
                rs.getLong("today_auto_execution_count"),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    static AutoMarketStatusResponse toStatus(
            boolean enabled,
            AutoMarketStatusCounts counts,
            List<AutoMarketConfigResponse> configs,
            List<AutoParticipantResponse> participants,
            List<AutoParticipantSymbolConfigResponse> participantSymbolConfigs,
            List<AutoParticipantProfileConfigResponse> participantProfileConfigs
    ) {
        return new AutoMarketStatusResponse(
                enabled,
                counts.configCount(),
                counts.participantCount(),
                counts.participantProfileConfigCount(),
                counts.enabledParticipantCount(),
                counts.salaryEligibleParticipantCount(),
                counts.openAutoOrderCount(),
                counts.todayAutoExecutionCount(),
                configs,
                participants,
                participantSymbolConfigs,
                participantProfileConfigs
        );
    }

    static AutoParticipantResponse toParticipant(ResultSet rs) throws SQLException {
        return new AutoParticipantResponse(
                rs.getString("user_key"),
                rs.getString("display_name"),
                rs.getBoolean("enabled"),
                defaultProfileTypeName(rs.getString("profile_type")),
                rs.getString("behavior_model_version"),
                rs.getObject("behavior_seed") == null ? null : rs.getString("behavior_seed"),
                rs.getBigDecimal("recurring_cash_amount"),
                rs.getBigDecimal("recurring_cash_interval_value"),
                rs.getString("recurring_cash_interval_unit"),
                rs.getObject("account_id", Long.class),
                rs.getString("account_status"),
                rs.getBigDecimal("cash_balance"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("withdrawn_at", LocalDateTime.class),
                rs.getBigDecimal("payday_available_budget"),
                rs.getBigDecimal("dividend_available_budget"),
                rs.getBigDecimal("funding_reserved_amount"),
                rs.getBigDecimal("funding_spent_amount"),
                rs.getLong("active_funding_budget_count"),
                rs.getLong("tracked_position_count"),
                rs.getBigDecimal("average_holding_trading_days"),
                rs.getLong("average_down_round_count"),
                rs.getBigDecimal("withdrawal_returned_cash_amount"),
                rs.getLong("withdrawal_returned_share_quantity"),
                rs.getInt("withdrawal_returned_symbol_count"),
                rs.getBoolean("account_closed_on_withdrawal")
        );
    }

    static AutoMarketConfigResponse toMarketConfig(StockAutoMarketConfig config) {
        return toMarketConfig(config, null);
    }

    static AutoMarketConfigResponse toMarketConfig(StockAutoMarketConfig config, AutoMarketDailyRegimeResponse dailyRegime) {
        return new AutoMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getMaxOrderQuantity() == null ? 0 : config.getMaxOrderQuantity(),
                config.getOrderTtlSeconds() == null ? 0 : config.getOrderTtlSeconds(),
                primaryRegimeCountWeights(config),
                primaryDistributionBias(config),
                secondaryDistributionBias(config),
                dailyRegime
        );
    }

    static AutoParticipantSymbolConfigResponse toParticipantSymbolConfig(StockAutoParticipantSymbolConfig config) {
        return new AutoParticipantSymbolConfigResponse(
                config.getUserKey(),
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getIntensity() == null ? 0 : config.getIntensity(),
                config.getUpdatedAt()
        );
    }

    static AutoParticipantSymbolConfigResponse toEffectiveParticipantSymbolConfig(
            String userKey,
            LocalDateTime participantUpdatedAt,
            StockAutoMarketConfig marketConfig,
            StockAutoParticipantSymbolConfig savedConfig
    ) {
        if (savedConfig != null) {
            return toParticipantSymbolConfig(savedConfig);
        }
        return new AutoParticipantSymbolConfigResponse(
                userKey,
                marketConfig.getSymbol(),
                true,
                5,
                participantUpdatedAt == null ? marketConfig.getUpdatedAt() : participantUpdatedAt
        );
    }

    private static String defaultProfileTypeName(String profileType) {
        return profileType == null ? AutoParticipantProfileType.defaultType().name() : profileType;
    }

    static AutoMarketDistributionBiasResponse primaryDistributionBias(StockAutoMarketConfig config) {
        return new AutoMarketDistributionBiasResponse(
                valueOrZero(config.getPrimaryPricePressureBias()),
                valueOrZero(config.getPrimaryAssetPreferencePressureBias()),
                valueOrZero(config.getPrimaryVolatilityPressureBias()),
                valueOrZero(config.getPrimaryLiquidityPressureBias()),
                valueOrZero(config.getPrimaryExecutionAggressionPressureBias())
        );
    }

    static AutoMarketRegimeCountWeightsResponse primaryRegimeCountWeights(
            StockAutoMarketConfig config
    ) {
        return new AutoMarketRegimeCountWeightsResponse(
                valueOrZero(config.getPrimaryRegimeCount1Weight()),
                valueOrZero(config.getPrimaryRegimeCount2Weight()),
                valueOrZero(config.getPrimaryRegimeCount3Weight()),
                config.getPrimaryRegimeCount4Weight() == null ? 100 : config.getPrimaryRegimeCount4Weight()
        );
    }

    static AutoMarketDistributionBiasResponse secondaryDistributionBias(StockAutoMarketConfig config) {
        return new AutoMarketDistributionBiasResponse(
                valueOrZero(config.getSecondaryPricePressureBias()),
                valueOrZero(config.getSecondaryAssetPreferencePressureBias()),
                valueOrZero(config.getSecondaryVolatilityPressureBias()),
                valueOrZero(config.getSecondaryLiquidityPressureBias()),
                valueOrZero(config.getSecondaryExecutionAggressionPressureBias())
        );
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    record AutoMarketStatusCounts(
            long configCount,
            long participantCount,
            long participantProfileConfigCount,
            long enabledParticipantCount,
            long salaryEligibleParticipantCount,
            long openAutoOrderCount,
            long todayAutoExecutionCount
    ) {
    }
}
