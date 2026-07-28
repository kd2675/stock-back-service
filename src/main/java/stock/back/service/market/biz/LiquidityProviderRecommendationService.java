package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.market.vo.LiquidityProviderRecommendationResponse;

@Service
public class LiquidityProviderRecommendationService {

    private static final BigDecimal SEED_INVENTORY_RATE = new BigDecimal("0.005000");
    private static final BigDecimal MIN_SEED_INVENTORY_RATE = new BigDecimal("0.001000");
    private static final BigDecimal MAX_SEED_INVENTORY_RATE = new BigDecimal("0.020000");

    private final JdbcClient jdbcClient;
    private final MarketReferenceVolumeResolver referenceVolumeResolver;

    public LiquidityProviderRecommendationService(
            JdbcClient jdbcClient,
            MarketReferenceVolumeResolver referenceVolumeResolver
    ) {
        this.jdbcClient = jdbcClient;
        this.referenceVolumeResolver = referenceVolumeResolver;
    }

    @Transactional(readOnly = true)
    public LiquidityProviderRecommendationResponse getRecommendation() {
        List<SymbolState> states = jdbcClient.sql(
                        """
                        select instrument.symbol,
                               instrument.tradable_shares,
                               price.current_price,
                               market.enabled as market_enabled,
                               market.market_status,
                               case when mandate.id is null then false else true end
                                   as existing_mandate,
                               eligible_source.account_id as source_account_id,
                               coalesce(
                                   eligible_source.available_quantity,
                                   0
                               ) as source_available_quantity,
                               case when eligible_source.account_id is null
                                   then false else true end as has_source
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                          join stock_price price
                            on price.symbol = instrument.symbol
                           and price.current_price > 0
                          left join stock_liquidity_mandate mandate
                            on mandate.symbol = instrument.symbol
                          left join (
                              select candidate.symbol,
                                     min(candidate.account_id) as account_id,
                                     max(
                                         holding.quantity
                                         - holding.reserved_quantity
                                     ) as available_quantity
                                from (
                                    select contract.symbol,
                                           contract.account_id
                                      from stock_underwriting_contract contract
                                     where contract.status in (
                                         'ALLOCATED', 'STABILIZING', 'COMPLETED'
                                     )
                                    union
                                    select allocation.symbol,
                                           allocation.destination_account_id
                                      from stock_security_allocation_ledger allocation
                                     where allocation.allocation_reason =
                                               'INITIAL_FLOAT_CUSTODY'
                                       and allocation.tradability_status = 'TRADABLE'
                                ) candidate
                                join stock_account source_account
                                  on source_account.id = candidate.account_id
                                 and source_account.status = 'ACTIVE'
                                 and source_account.participant_category in (
                                     'ISSUE_UNDERWRITER', 'SYSTEM_CUSTODY'
                                 )
                                join stock_holding holding
                                  on holding.account_id = candidate.account_id
                                 and holding.symbol = candidate.symbol
                                 and holding.quantity > holding.reserved_quantity
                               group by candidate.symbol
                              having count(distinct candidate.account_id) = 1
                          ) eligible_source
                            on eligible_source.symbol = instrument.symbol
                         where instrument.enabled = true
                           and instrument.issued_shares > 0
                           and instrument.tradable_shares > 0
                         order by instrument.symbol
                        """
                )
                .query((rs, rowNum) -> new SymbolState(
                        rs.getString("symbol"),
                        rs.getLong("tradable_shares"),
                        rs.getBigDecimal("current_price"),
                        rs.getBoolean("market_enabled"),
                        rs.getString("market_status"),
                        rs.getBoolean("existing_mandate"),
                        nullableLong(rs.getObject("source_account_id")),
                        rs.getLong("source_available_quantity"),
                        rs.getBoolean("has_source")
                ))
                .list();
        Map<String, MarketReferenceVolumeResolver.Resolution> referenceVolumes =
                referenceVolumeResolver.resolve(states.stream()
                        .map(state -> new MarketReferenceVolumeResolver.SymbolFloat(
                                state.symbol(),
                                state.tradableShares()
                        ))
                        .toList());
        List<LiquidityProviderRecommendationResponse.Symbol> symbols = states.stream()
                .map(state -> toRecommendation(
                        state,
                        referenceVolumes.get(state.symbol())
                ))
                .toList();
        int recommendedCount = Math.toIntExact(states.stream()
                .filter(state -> isConfiguredOrPendingEligible(
                        state.marketEnabled(),
                        state.marketStatus(),
                        state.hasSource()
                ))
                .count());
        long currentCount = symbols.stream()
                .filter(LiquidityProviderRecommendationResponse.Symbol::existingMandate)
                .count();
        return new LiquidityProviderRecommendationResponse(
                recommendedCount,
                currentCount,
                Math.max(0L, recommendedCount - currentCount),
                MarketReferenceVolumeResolver.FALLBACK_FLOAT_RATE,
                MarketReferenceVolumeResolver.MIN_FLOAT_RATE,
                MarketReferenceVolumeResolver.MAX_FLOAT_RATE,
                SEED_INVENTORY_RATE,
                MIN_SEED_INVENTORY_RATE,
                MAX_SEED_INVENTORY_RATE,
                BigDecimal.ONE.setScale(6),
                symbols
        );
    }

    private LiquidityProviderRecommendationResponse.Symbol toRecommendation(
            SymbolState state,
            MarketReferenceVolumeResolver.Resolution referenceVolume
    ) {
        if (referenceVolume == null) {
            throw new IllegalStateException(
                    "Reference daily volume was not resolved for " + state.symbol()
            );
        }
        long referenceDailyVolume = referenceVolume.referenceDailyVolume();
        long seedQuantity = scaledQuantity(
                state.tradableShares(),
                SEED_INVENTORY_RATE
        );
        BigDecimal initialCash = state.currentPrice()
                .multiply(BigDecimal.valueOf(seedQuantity))
                .setScale(2, RoundingMode.HALF_UP);
        boolean marketEligible = isConfiguredOrPendingEligible(
                state.marketEnabled(),
                state.marketStatus(),
                state.hasSource()
        );
        String reason;
        if (state.existingMandate()) {
            reason = "ALREADY_CREATED";
        } else if (!marketEligible) {
            reason = "LIQUIDITY_SOURCE_REQUIRED";
        } else if (state.sourceAccountId() == null) {
            reason = "SOURCE_ACCOUNT_REQUIRED";
        } else if (state.sourceAvailableQuantity() < seedQuantity) {
            reason = "SOURCE_INVENTORY_SHORTAGE";
        } else {
            reason = "READY";
        }
        return new LiquidityProviderRecommendationResponse.Symbol(
                state.symbol(),
                state.tradableShares(),
                state.currentPrice(),
                state.marketEnabled(),
                state.marketStatus(),
                state.existingMandate(),
                state.sourceAccountId(),
                state.sourceAvailableQuantity(),
                referenceDailyVolume,
                referenceVolume.referenceDailyVolumeRate(),
                referenceVolume.completedHistoryDays(),
                referenceVolume.source(),
                seedQuantity,
                initialCash,
                "READY".equals(reason),
                reason
        );
    }

    private boolean isConfiguredOrPendingEligible(
            boolean marketEnabled,
            String marketStatus,
            boolean hasSource
    ) {
        if (marketEnabled && ("OPEN".equals(marketStatus) || "CLOSED".equals(marketStatus))) {
            return true;
        }
        return !marketEnabled && "CLOSED".equals(marketStatus) && hasSource;
    }

    private long scaledQuantity(long tradableShares, BigDecimal rate) {
        return Math.max(
                1L,
                BigDecimal.valueOf(tradableShares)
                        .multiply(rate)
                        .setScale(0, RoundingMode.DOWN)
                        .longValueExact()
        );
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private record SymbolState(
            String symbol,
            long tradableShares,
            BigDecimal currentPrice,
            boolean marketEnabled,
            String marketStatus,
            boolean existingMandate,
            Long sourceAccountId,
            long sourceAvailableQuantity,
            boolean hasSource
    ) {
    }
}
