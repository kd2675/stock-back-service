package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantProfileConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketStatusResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;
import stock.back.service.market.vo.ListingAutoAccountResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AutoMarketStatusQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockAutoParticipantProfileConfigRepository stockAutoParticipantProfileConfigRepository;
    private final StockAutoParticipantRepository stockAutoParticipantRepository;
    private final StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository;
    private final StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;
    private final StockOrderRepository stockOrderRepository;
    private final StockExecutionMarketViewRepository stockExecutionMarketViewRepository;
    private final ListingAutoAccountLedgerQueryService listingAutoAccountLedgerQueryService;

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus() {
        return getAutoMarketStatus(true, true, true, true, true, true, true, null);
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus(boolean includeParticipantSymbolConfigs) {
        return getAutoMarketStatus(true, true, includeParticipantSymbolConfigs, true, true, true, true, null);
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus(
            boolean includeConfigs,
            boolean includeParticipants,
            boolean includeParticipantSymbolConfigs,
            boolean includeParticipantProfileConfigs,
            boolean includeListingAutoAccounts,
            boolean includeRuntimeMetrics
    ) {
        return getAutoMarketStatus(
                includeConfigs,
                includeParticipants,
                includeParticipantSymbolConfigs,
                includeParticipantProfileConfigs,
                includeListingAutoAccounts,
                includeRuntimeMetrics,
                true,
                null
        );
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus(
            boolean includeConfigs,
            boolean includeParticipants,
            boolean includeParticipantSymbolConfigs,
            boolean includeParticipantProfileConfigs,
            boolean includeListingAutoAccounts,
            boolean includeRuntimeMetrics,
            boolean includeSalaryEligibility,
            String participantSymbolConfigUserKey
    ) {
        String normalizedParticipantSymbolConfigUserKey = normalizeOptionalText(participantSymbolConfigUserKey);
        boolean shouldLoadConfigs = includeConfigs || includeParticipantSymbolConfigs;
        boolean shouldLoadParticipants = includeParticipants
                || (includeParticipantSymbolConfigs && normalizedParticipantSymbolConfigUserKey == null);
        if (!shouldLoadConfigs
                && !shouldLoadParticipants
                && !includeParticipantSymbolConfigs
                && !includeParticipantProfileConfigs
                && !includeListingAutoAccounts
                && normalizedParticipantSymbolConfigUserKey == null) {
            return getAutoMarketSummaryStatus(includeRuntimeMetrics, includeSalaryEligibility);
        }
        List<StockAutoMarketConfig> configEntities = shouldLoadConfigs
                ? stockAutoMarketConfigRepository.findAll().stream()
                        .sorted((left, right) -> left.getSymbol().compareTo(right.getSymbol()))
                        .toList()
                : List.of();
        List<AutoMarketConfigResponse> configs = shouldLoadConfigs
                ? configEntities.stream()
                        .map(this::toAutoMarketConfigResponse)
                        .toList()
                : List.of();
        List<AutoParticipantResponse> participants = shouldLoadParticipants
                ? loadAutoParticipantStatusResponses()
                : List.of();
        List<AutoParticipantSymbolConfigTarget> participantSymbolConfigTargets = includeParticipantSymbolConfigs
                ? resolveAutoParticipantSymbolConfigTargets(participants, normalizedParticipantSymbolConfigUserKey)
                : List.of();
        List<AutoParticipantSymbolConfigResponse> participantSymbolConfigs = includeParticipantSymbolConfigs
                ? loadEffectiveAutoParticipantSymbolConfigs(participantSymbolConfigTargets, configEntities)
                : List.of();
        List<AutoParticipantProfileConfigResponse> participantProfileConfigs = includeParticipantProfileConfigs
                ? getAutoParticipantProfileConfigs()
                : List.of();
        List<ListingAutoAccountResponse> listingAutoAccounts = includeListingAutoAccounts
                ? toListingAutoAccountResponses(stockListingAutoAccountConfigRepository.findAllByOrderBySymbolAsc())
                : List.of();
        long configCount = shouldLoadConfigs ? configEntities.size() : stockAutoMarketConfigRepository.count();
        long participantCount = shouldLoadParticipants ? participants.size() : stockAutoParticipantRepository.countByWithdrawnAtIsNull();
        long participantProfileConfigCount = AutoParticipantProfileType.values().length;
        long listingAutoAccountCount = includeListingAutoAccounts ? listingAutoAccounts.size() : stockListingAutoAccountConfigRepository.count();
        long enabledParticipantCount = shouldLoadParticipants
                ? participants.stream().filter(AutoParticipantResponse::enabled).count()
                : stockAutoParticipantRepository.countByEnabledTrueAndWithdrawnAtIsNull();
        long salaryEligibleParticipantCount = includeSalaryEligibility ? countSalaryEligibleAutoParticipants() : 0L;
        long enabledConfigCount = shouldLoadConfigs
                ? configEntities.stream().filter(config -> Boolean.TRUE.equals(config.getEnabled())).count()
                : 0L;
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        long openAutoOrderCount = includeRuntimeMetrics ? stockOrderRepository.countOpenAutoOrders(openStatuses, MarketType.ORDER_BOOK) : 0L;
        long todayAutoExecutionCount = includeRuntimeMetrics ? stockExecutionMarketViewRepository.countAutoExecutionsFrom(LocalDate.now().atStartOfDay()) : 0L;
        boolean enabled = enabledParticipantCount > 0 && (shouldLoadConfigs
                ? enabledConfigCount > 0
                : stockAutoMarketConfigRepository.existsByEnabledTrue());
        return new AutoMarketStatusResponse(
                enabled,
                configCount,
                participantCount,
                participantProfileConfigCount,
                listingAutoAccountCount,
                enabledParticipantCount,
                salaryEligibleParticipantCount,
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
                .map(profileType -> AutoParticipantProfileConfigResponseMapper.toResponse(profileType, savedConfigs.get(profileType)))
                .toList();
    }

    private AutoMarketStatusResponse getAutoMarketSummaryStatus(boolean includeRuntimeMetrics, boolean includeSalaryEligibility) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        String runtimeMetricSql = includeRuntimeMetrics
                ? """
                       (select count(*)
                          from stock_order o
                         where o.market_type = 'ORDER_BOOK'
                           and o.status in ('PENDING', 'PARTIALLY_FILLED')
                           and exists (
                               select 1
                                 from stock_account a
                                 join stock_auto_participant p on p.user_key = a.user_key
                                where a.id = o.account_id
                                  and p.enabled = true
                                  and p.withdrawn_at is null
                           )) as open_auto_order_count,
                       (select count(*)
                          from stock_execution e
                         where e.executed_at >= ?
                           and exists (
                               select 1
                                 from stock_account a
                                 join stock_auto_participant p on p.user_key = a.user_key
                                where a.id = e.account_id
                                  and p.enabled = true
                                  and p.withdrawn_at is null
                           )) as today_auto_execution_count
                        """
                : """
                       0 as open_auto_order_count,
                       0 as today_auto_execution_count
                        """;
        String salaryEligibilitySql = includeSalaryEligibility
                ? """
                       (select count(*)
                          from stock_auto_participant p
                          join stock_account a on a.user_key = p.user_key and a.status = 'ACTIVE'
                          left join stock_auto_participant_profile_config pc on pc.profile_type = p.profile_type
                         where p.enabled = true
                           and p.withdrawn_at is null
                           and (
                               coalesce(p.recurring_cash_amount, 0) > 0
                               or coalesce(pc.recurring_deposit_amount, 0) > 0
                           )) as salary_eligible_participant_count
                        """
                : "0 as salary_eligible_participant_count";
        String sql = """
                select (select count(*) from stock_auto_market_config) as config_count,
                       (select count(*)
                          from stock_auto_market_config c
                         where c.enabled = true) as enabled_config_count,
                       (select count(*)
                          from stock_auto_participant p
                         where p.withdrawn_at is null) as participant_count,
                       (select count(*)
                          from stock_auto_participant p
                         where p.enabled = true
                           and p.withdrawn_at is null) as enabled_participant_count,
                       (select count(*) from stock_listing_auto_account_config) as listing_auto_account_count,
                       %s,
                       %s
                """.formatted(salaryEligibilitySql, runtimeMetricSql);
        return includeRuntimeMetrics
                ? jdbcTemplate.queryForObject(sql, (rs, rowNum) -> toAutoMarketSummaryStatus(rs), todayStart)
                : jdbcTemplate.queryForObject(sql, (rs, rowNum) -> toAutoMarketSummaryStatus(rs));
    }

    private AutoMarketStatusResponse toAutoMarketSummaryStatus(ResultSet rs) throws SQLException {
        long enabledParticipantCount = rs.getLong("enabled_participant_count");
        return new AutoMarketStatusResponse(
                enabledParticipantCount > 0 && rs.getLong("enabled_config_count") > 0,
                rs.getLong("config_count"),
                rs.getLong("participant_count"),
                AutoParticipantProfileType.values().length,
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

    private long countSalaryEligibleAutoParticipants() {
        String sql = """
                select count(*)
                  from stock_auto_participant p
                  join stock_account a on a.user_key = p.user_key and a.status = 'ACTIVE'
                  left join stock_auto_participant_profile_config pc on pc.profile_type = p.profile_type
                 where p.enabled = true
                   and p.withdrawn_at is null
                   and (
                       coalesce(p.recurring_cash_amount, 0) > 0
                       or coalesce(pc.recurring_deposit_amount, 0) > 0
                   )
                """;
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count == null ? 0L : count;
    }

    private List<AutoParticipantResponse> loadAutoParticipantStatusResponses() {
        String sql = """
                select p.user_key,
                       p.display_name,
                       p.enabled,
                       p.profile_type,
                       p.recurring_cash_amount,
                       p.recurring_cash_interval_value,
                       p.recurring_cash_interval_unit,
                       p.created_at,
                       p.updated_at,
                       p.withdrawn_at,
                       a.id as account_id,
                       a.status as account_status,
                       a.cash_balance
                  from stock_auto_participant p
                  left join stock_account a on a.user_key = p.user_key
                 where p.withdrawn_at is null
                 order by p.user_key asc
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AutoParticipantResponse(
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
        ));
    }

    private String defaultProfileTypeName(String profileType) {
        return profileType == null ? AutoParticipantProfileType.defaultType().name() : profileType;
    }

    private List<AutoParticipantSymbolConfigTarget> resolveAutoParticipantSymbolConfigTargets(
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
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AutoParticipantSymbolConfigTarget(
                rs.getString("user_key"),
                rs.getObject("updated_at", LocalDateTime.class)
        ), participantSymbolConfigUserKey);
    }

    private List<AutoParticipantSymbolConfigResponse> loadEffectiveAutoParticipantSymbolConfigs(
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
                        .map(config -> toEffectiveAutoParticipantSymbolConfigResponse(
                                participantTarget,
                                config,
                                savedParticipantSymbolConfigs.get(autoParticipantSymbolConfigKey(participantTarget.userKey(), config.getSymbol()))
                        )))
                .toList();
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
            AutoParticipantSymbolConfigTarget participant,
            StockAutoMarketConfig marketConfig,
            StockAutoParticipantSymbolConfig savedConfig
    ) {
        if (savedConfig != null) {
            return toAutoParticipantSymbolConfigResponse(savedConfig);
        }
        return new AutoParticipantSymbolConfigResponse(
                participant.userKey(),
                marketConfig.getSymbol(),
                true,
                marketConfig.getIntensity() == null ? 5 : marketConfig.getIntensity(),
                participant.updatedAt() == null ? marketConfig.getUpdatedAt() : participant.updatedAt()
        );
    }

    private List<ListingAutoAccountResponse> toListingAutoAccountResponses(List<StockListingAutoAccountConfig> configs) {
        if (configs.isEmpty()) {
            return List.of();
        }
        Map<String, ListingAutoAccountLedger> ledgersBySymbol = listingAutoAccountLedgerQueryService.findLedgersBySymbol();
        return configs.stream()
                .map(config -> toListingAutoAccountResponse(
                        config,
                        ledgersBySymbol.getOrDefault(config.getSymbol(), ListingAutoAccountLedger.empty())
                ))
                .toList();
    }

    private ListingAutoAccountResponse toListingAutoAccountResponse(
            StockListingAutoAccountConfig config,
            ListingAutoAccountLedger ledger
    ) {
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

    private String autoParticipantSymbolConfigKey(String userKey, String symbol) {
        return userKey + "\n" + symbol;
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record AutoParticipantSymbolConfigTarget(
            String userKey,
            LocalDateTime updatedAt
    ) {
    }
}
