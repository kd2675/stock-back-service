package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.market.vo.AutoParticipantProfileOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileSymbolHoldingResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AutoParticipantProfileOverviewQueryService {

    private final JdbcClient jdbcClient;
    private final AutoParticipantAggregateQuerySupport aggregateQuerySupport;
    private final SimulationClockService simulationClockService;

    public AutoParticipantProfileOverviewQueryService(
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService
    ) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
        this.aggregateQuerySupport = new AutoParticipantAggregateQuerySupport(jdbcClient);
        this.simulationClockService = simulationClockService;
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantProfileOverviewResponse> getAutoParticipantProfileOverviews() {
        LocalDateTime todayStart = simulationClockService.currentMarketDayStart();
        List<ParticipantRow> participants = findParticipants();
        Map<String, ProfileAccumulator> profiles = new LinkedHashMap<>();
        Map<Long, ProfileAccumulator> profileByAccountId = new HashMap<>();
        Map<String, ProfileAccumulator> profileByUserKey = new HashMap<>();

        for (ParticipantRow participant : participants) {
            ProfileAccumulator profile = profiles.computeIfAbsent(participant.profileType(), ProfileAccumulator::new);
            profile.totalCount++;
            if (participant.enabled()) {
                profile.enabledCount++;
            }
            profile.availableCash = profile.availableCash.add(participant.availableCash());
            profileByUserKey.put(participant.userKey(), profile);
            if (participant.accountId() != null) {
                profile.accountCount++;
                profileByAccountId.put(participant.accountId(), profile);
            }
        }

        if (!profileByAccountId.isEmpty()) {
            aggregateQuerySupport.applyAccountAggregates(
                    profileByAccountId.keySet().stream().toList(),
                    todayStart,
                    profileByAccountId
            );
        }
        if (!profileByUserKey.isEmpty()) {
            aggregateQuerySupport.applyStrategyAggregates(
                    profileByUserKey.keySet().stream().toList(),
                    profileByUserKey
            );
        }

        return profiles.values().stream()
                .map(ProfileAccumulator::toResponse)
                .toList();
    }

    private List<ParticipantRow> findParticipants() {
        return jdbcClient.sql("""
                        select p.user_key,
                               p.enabled,
                               p.profile_type,
                               a.id as account_id,
                               coalesce(a.cash_balance, 0) as available_cash
                          from stock_auto_participant p
                          left join stock_account a on a.user_key = p.user_key
                         where p.withdrawn_at is null
                         order by p.profile_type asc, p.user_key asc
                        """)
                .query((rs, rowNum) -> new ParticipantRow(
                        rs.getString("user_key"),
                        rs.getBoolean("enabled"),
                        rs.getString("profile_type"),
                        rs.getObject("account_id", Long.class),
                        AutoParticipantQuerySupport.zeroIfNull(rs.getBigDecimal("available_cash"))
                ))
                .list();
    }

    private record ParticipantRow(
            String userKey,
            boolean enabled,
            String profileType,
            Long accountId,
            BigDecimal availableCash
    ) {
    }

    private static final class ProfileAccumulator extends AutoParticipantAggregateAccumulator {
        private final String profileType;
        private long totalCount;
        private long enabledCount;
        private long accountCount;
        private BigDecimal availableCash = BigDecimal.ZERO;
        private final Map<String, SymbolHoldingAccumulator> symbolHoldings = new HashMap<>();

        private ProfileAccumulator(String profileType) {
            this.profileType = profileType;
        }

        @Override
        protected void afterHoldingSummaryAdded(
                String symbol,
                long quantity,
                long reservedQuantity,
                long availableQuantity,
                BigDecimal marketValue,
                BigDecimal unrealizedProfit
        ) {
            symbolHoldings.computeIfAbsent(symbol, SymbolHoldingAccumulator::new)
                    .add(quantity, reservedQuantity, availableQuantity, marketValue, unrealizedProfit);
        }

        private AutoParticipantProfileOverviewResponse toResponse() {
            AutoParticipantAssetSummary assetSummary = aggregate.toAssetSummary(availableCash);
            List<AutoParticipantProfileSymbolHoldingResponse> topSymbolHoldings = symbolHoldings.values().stream()
                    .sorted(Comparator.comparing(SymbolHoldingAccumulator::marketValue).reversed()
                            .thenComparing(SymbolHoldingAccumulator::symbol))
                    .limit(3)
                    .map(SymbolHoldingAccumulator::toResponse)
                    .toList();
            return new AutoParticipantProfileOverviewResponse(
                    profileType,
                    totalCount,
                    enabledCount,
                    totalCount - enabledCount,
                    accountCount,
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
                    topSymbolHoldings
            );
        }
    }

    private static final class SymbolHoldingAccumulator {
        private final String symbol;
        private long holderCount;
        private long quantity;
        private long reservedQuantity;
        private long availableQuantity;
        private BigDecimal marketValue = BigDecimal.ZERO;
        private BigDecimal unrealizedProfit = BigDecimal.ZERO;

        private SymbolHoldingAccumulator(String symbol) {
            this.symbol = symbol;
        }

        private void add(
                long nextQuantity,
                long nextReservedQuantity,
                long nextAvailableQuantity,
                BigDecimal nextMarketValue,
                BigDecimal nextUnrealizedProfit
        ) {
            holderCount++;
            quantity += nextQuantity;
            reservedQuantity += nextReservedQuantity;
            availableQuantity += nextAvailableQuantity;
            marketValue = marketValue.add(nextMarketValue);
            unrealizedProfit = unrealizedProfit.add(nextUnrealizedProfit);
        }

        private String symbol() {
            return symbol;
        }

        private BigDecimal marketValue() {
            return marketValue;
        }

        private AutoParticipantProfileSymbolHoldingResponse toResponse() {
            return new AutoParticipantProfileSymbolHoldingResponse(
                    symbol,
                    holderCount,
                    quantity,
                    reservedQuantity,
                    availableQuantity,
                    marketValue,
                    unrealizedProfit
            );
        }
    }
}
