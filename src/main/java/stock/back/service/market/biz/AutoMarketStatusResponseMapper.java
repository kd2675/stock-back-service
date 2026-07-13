package stock.back.service.market.biz;

import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketDailyRegimeResponse;
import stock.back.service.market.vo.AutoMarketStatusResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;
import stock.back.service.market.vo.ListingAutoAccountResponse;

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
                rs.getLong("listing_auto_account_count"),
                enabledParticipantCount,
                rs.getLong("salary_eligible_participant_count"),
                rs.getLong("open_auto_order_count"),
                rs.getLong("today_auto_execution_count"),
                List.of(),
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
            List<AutoParticipantProfileConfigResponse> participantProfileConfigs,
            List<ListingAutoAccountResponse> listingAutoAccounts
    ) {
        return new AutoMarketStatusResponse(
                enabled,
                counts.configCount(),
                counts.participantCount(),
                counts.participantProfileConfigCount(),
                counts.listingAutoAccountCount(),
                counts.enabledParticipantCount(),
                counts.salaryEligibleParticipantCount(),
                counts.openAutoOrderCount(),
                counts.todayAutoExecutionCount(),
                configs,
                participants,
                participantSymbolConfigs,
                participantProfileConfigs,
                listingAutoAccounts
        );
    }

    static AutoParticipantResponse toParticipant(ResultSet rs) throws SQLException {
        return new AutoParticipantResponse(
                rs.getString("user_key"),
                rs.getString("display_name"),
                rs.getBoolean("enabled"),
                defaultProfileTypeName(rs.getString("profile_type")),
                rs.getBigDecimal("recurring_cash_amount"),
                rs.getBigDecimal("recurring_cash_interval_value"),
                rs.getString("recurring_cash_interval_unit"),
                rs.getObject("account_id", Long.class),
                rs.getString("account_status"),
                rs.getBigDecimal("cash_balance"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("withdrawn_at", LocalDateTime.class)
        );
    }

    static AutoMarketConfigResponse toMarketConfig(StockAutoMarketConfig config) {
        return toMarketConfig(config, null);
    }

    static AutoMarketConfigResponse toMarketConfig(StockAutoMarketConfig config, AutoMarketDailyRegimeResponse dailyRegime) {
        return new AutoMarketConfigResponse(
                config.getSymbol(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getIntensity() == null ? 0 : config.getIntensity(),
                config.getMaxOrderQuantity() == null ? 0 : config.getMaxOrderQuantity(),
                config.getOrderTtlSeconds() == null ? 0 : config.getOrderTtlSeconds(),
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
                marketConfig.getIntensity() == null ? 5 : marketConfig.getIntensity(),
                participantUpdatedAt == null ? marketConfig.getUpdatedAt() : participantUpdatedAt
        );
    }

    static ListingAutoAccountResponse toListingAutoAccount(
            StockListingAutoAccountConfig config,
            ListingAutoAccountLedger ledger,
            long issuedShares
    ) {
        return new ListingAutoAccountResponse(
                config.getSymbol(),
                config.getUserKey(),
                config.getDisplayName(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getPositionSide(),
                issuedShares,
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
                config.getTargetBuyQuantity(),
                config.getTargetSellQuantity(),
                config.getTargetHoldingQuantity(),
                config.getInventoryBandQuantity(),
                ledger.openBuyQuantity(),
                ledger.openSellQuantity(),
                config.getBuyPriceOffsetDirection(),
                config.getSellPriceOffsetDirection(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private static String defaultProfileTypeName(String profileType) {
        return profileType == null ? AutoParticipantProfileType.defaultType().name() : profileType;
    }

    record AutoMarketStatusCounts(
            long configCount,
            long participantCount,
            long participantProfileConfigCount,
            long listingAutoAccountCount,
            long enabledParticipantCount,
            long salaryEligibleParticipantCount,
            long openAutoOrderCount,
            long todayAutoExecutionCount
    ) {
    }
}
