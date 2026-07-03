package stock.back.service.market.biz;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.market.vo.AutoParticipantHoldingGroupResponse;
import stock.back.service.market.vo.AutoParticipantHoldingResponse;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileOverviewResponse;
import stock.back.service.market.vo.AutoParticipantResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AutoParticipantOverviewQueryService {

    private final JdbcClient jdbcClient;
    private final AutoParticipantAggregateQuerySupport aggregateQuerySupport;
    private final AutoMarketStatusDataLoader autoMarketStatusDataLoader;
    private final AutoParticipantHoldingQueryService autoParticipantHoldingQueryService;
    private final AutoParticipantProfileOverviewQueryService autoParticipantProfileOverviewQueryService;
    private final SimulationClockService simulationClockService;

    public AutoParticipantOverviewQueryService(
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            AutoMarketStatusDataLoader autoMarketStatusDataLoader,
            AutoParticipantHoldingQueryService autoParticipantHoldingQueryService,
            AutoParticipantProfileOverviewQueryService autoParticipantProfileOverviewQueryService,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(namedParameterJdbcTemplate);
        this.aggregateQuerySupport = new AutoParticipantAggregateQuerySupport(jdbcClient);
        this.autoMarketStatusDataLoader = autoMarketStatusDataLoader;
        this.autoParticipantHoldingQueryService = autoParticipantHoldingQueryService;
        this.autoParticipantProfileOverviewQueryService = autoParticipantProfileOverviewQueryService;
        this.simulationClockService = simulationClockService;
    }

    public List<AutoParticipantProfileOverviewResponse> getAutoParticipantProfileOverviews() {
        return autoParticipantProfileOverviewQueryService.getAutoParticipantProfileOverviews();
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantResponse> getAutoParticipants() {
        return autoMarketStatusDataLoader.loadAutoParticipantStatusResponses();
    }

    public List<AutoParticipantOverviewResponse> getAutoParticipantOverviews(boolean includeHoldings, List<String> userKeys) {
        LocalDateTime todayStart = simulationClockService.currentMarketDayStart();
        List<String> normalizedUserKeys = AutoParticipantQuerySupport.normalizeUserKeys(userKeys);
        List<ParticipantOverviewAccumulator> participants = findParticipantOverviewSeeds(normalizedUserKeys);
        Map<String, ParticipantOverviewAccumulator> participantByUserKey = new LinkedHashMap<>();
        Map<Long, ParticipantOverviewAccumulator> participantByAccountId = new HashMap<>();
        for (ParticipantOverviewAccumulator participant : participants) {
            participantByUserKey.put(participant.userKey, participant);
            if (participant.accountId != null) {
                participantByAccountId.put(participant.accountId, participant);
            }
        }

        if (!participantByAccountId.isEmpty()) {
            aggregateQuerySupport.applyAccountAggregates(
                    participantByAccountId.keySet().stream().toList(),
                    todayStart,
                    participantByAccountId
            );
        }
        if (!participantByUserKey.isEmpty()) {
            aggregateQuerySupport.applyStrategyAggregates(
                    participantByUserKey.keySet().stream().toList(),
                    participantByUserKey
            );
        }

        List<AutoParticipantOverviewResponse> overviews = participants.stream()
                .map(ParticipantOverviewAccumulator::toResponse)
                .toList();
        if (!includeHoldings) {
            return overviews;
        }
        Map<Long, List<AutoParticipantHoldingResponse>> holdingsByAccountId = autoParticipantHoldingQueryService.findHoldingsByAccountIds(overviews.stream()
                .map(AutoParticipantOverviewResponse::accountId)
                .filter(accountId -> accountId != null)
                .distinct()
                .toList());
        return AutoParticipantOverviewResponseMapper.withHoldingsByAccountId(overviews, holdingsByAccountId);
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantHoldingGroupResponse> getAutoParticipantHoldings(List<String> userKeys) {
        return autoParticipantHoldingQueryService.getAutoParticipantHoldings(userKeys);
    }

    private List<ParticipantOverviewAccumulator> findParticipantOverviewSeeds(List<String> userKeys) {
        String participantUserFilter = userKeys.isEmpty() ? "" : " and p.user_key in (:userKeys)";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        select p.user_key,
                               p.display_name,
                               p.enabled,
                               p.profile_type,
                               p.created_at,
                               p.updated_at,
                               p.withdrawn_at,
                               a.id as account_id,
                               a.status as account_status,
                               coalesce(a.cash_balance, 0) as available_cash
                          from stock_auto_participant p
                          left join stock_account a on a.user_key = p.user_key
                         where p.withdrawn_at is null
                         %s
                         order by p.user_key asc
                        """.formatted(participantUserFilter));
        if (!userKeys.isEmpty()) {
            statement = statement.param("userKeys", userKeys);
        }
        return statement
                .query((rs, rowNum) -> new ParticipantOverviewAccumulator(
                        rs.getString("user_key"),
                        rs.getString("display_name"),
                        rs.getBoolean("enabled"),
                        rs.getString("profile_type"),
                        rs.getObject("account_id", Long.class),
                        rs.getString("account_status"),
                        AutoParticipantQuerySupport.zeroIfNull(rs.getBigDecimal("available_cash")),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class),
                        rs.getObject("withdrawn_at", LocalDateTime.class)
                ))
                .list();
    }

    private static final class ParticipantOverviewAccumulator extends AutoParticipantAggregateAccumulator {
        private final String userKey;
        private final String displayName;
        private final boolean enabled;
        private final String profileType;
        private final Long accountId;
        private final String accountStatus;
        private final BigDecimal availableCash;
        private final LocalDateTime createdAt;
        private final LocalDateTime updatedAt;
        private final LocalDateTime withdrawnAt;

        private ParticipantOverviewAccumulator(
                String userKey,
                String displayName,
                boolean enabled,
                String profileType,
                Long accountId,
                String accountStatus,
                BigDecimal availableCash,
                LocalDateTime createdAt,
                LocalDateTime updatedAt,
                LocalDateTime withdrawnAt
        ) {
            this.userKey = userKey;
            this.displayName = displayName;
            this.enabled = enabled;
            this.profileType = profileType;
            this.accountId = accountId;
            this.accountStatus = accountStatus;
            this.availableCash = availableCash;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.withdrawnAt = withdrawnAt;
        }

        private AutoParticipantOverviewResponse toResponse() {
            AutoParticipantAssetSummary assetSummary = aggregate.toAssetSummary(availableCash);
            return new AutoParticipantOverviewResponse(
                    userKey,
                    displayName,
                    enabled,
                    profileType,
                    accountId,
                    accountStatus,
                    availableCash,
                    aggregate.reservedBuyCash(),
                    aggregate.holdingMarketValue(),
                    assetSummary.estimatedTotalAsset(),
                    aggregate.netCashFlow(),
                    assetSummary.totalProfit(),
                    assetSummary.returnRate(),
                    aggregate.holdingCount(),
                    aggregate.totalHoldingQuantity(),
                    aggregate.reservedSellQuantity(),
                    List.of(),
                    aggregate.openOrderCount(),
                    aggregate.openBuyOrderCount(),
                    aggregate.openSellOrderCount(),
                    aggregate.openBuyQuantity(),
                    aggregate.openSellQuantity(),
                    aggregate.todayExecutionCount(),
                    aggregate.todayBuyQuantity(),
                    aggregate.todaySellQuantity(),
                    aggregate.todayGrossAmount(),
                    aggregate.strategyCount(),
                    aggregate.enabledStrategyCount(),
                    aggregate.lastOrderAt(),
                    aggregate.lastExecutionAt(),
                    createdAt,
                    updatedAt,
                    withdrawnAt
            );
        }
    }
}
