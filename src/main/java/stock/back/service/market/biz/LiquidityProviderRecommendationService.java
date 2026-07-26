package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.market.vo.LiquidityProviderRecommendationResponse;

@Service
public class LiquidityProviderRecommendationService {

    private static final BigDecimal REFERENCE_VOLUME_RATE = new BigDecimal("0.030000");
    private static final BigDecimal MIN_REFERENCE_VOLUME_RATE = new BigDecimal("0.005000");
    private static final BigDecimal MAX_REFERENCE_VOLUME_RATE = new BigDecimal("0.080000");
    private static final BigDecimal SEED_INVENTORY_RATE = new BigDecimal("0.005000");
    private static final BigDecimal MIN_SEED_INVENTORY_RATE = new BigDecimal("0.001000");
    private static final BigDecimal MAX_SEED_INVENTORY_RATE = new BigDecimal("0.020000");

    private final JdbcClient jdbcClient;

    public LiquidityProviderRecommendationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
                               case
                                 when underwriter.account_id is not null
                                 then underwriter.account_id
                                 else float_source.account_id
                               end as source_account_id,
                               case
                                 when underwriter.account_id is not null
                                 then greatest(
                                     coalesce(underwriter_holding.quantity, 0)
                                     - coalesce(
                                         underwriter_holding.reserved_quantity,
                                         0
                                     ),
                                     0
                                 )
                                 else greatest(
                                     coalesce(float_holding.quantity, 0)
                                     - coalesce(float_holding.reserved_quantity, 0),
                                     0
                                 )
                               end as source_available_quantity,
                               case
                                 when underwriter.account_id is not null
                                   or float_source.account_id is not null
                                 then true
                                 else false
                               end as has_source
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                          join stock_price price
                            on price.symbol = instrument.symbol
                           and price.current_price > 0
                          left join stock_liquidity_mandate mandate
                            on mandate.symbol = instrument.symbol
                          left join (
                              select symbol, max(id) as contract_id
                                from stock_underwriting_contract
                               where status in (
                                   'ALLOCATED', 'STABILIZING', 'COMPLETED'
                               )
                               group by symbol
                          ) latest_underwriter
                            on latest_underwriter.symbol = instrument.symbol
                          left join stock_underwriting_contract underwriter
                            on underwriter.id = latest_underwriter.contract_id
                          left join stock_holding underwriter_holding
                            on underwriter_holding.account_id = underwriter.account_id
                           and underwriter_holding.symbol = instrument.symbol
                          left join (
                              select allocation.symbol,
                                     max(allocation.destination_account_id) as account_id
                                from stock_security_allocation_ledger allocation
                                join stock_account source_account
                                  on source_account.id = allocation.destination_account_id
                                 and source_account.status = 'ACTIVE'
                                 and source_account.participant_category = 'SYSTEM_CUSTODY'
                               where allocation.allocation_reason = 'INITIAL_FLOAT_CUSTODY'
                                 and allocation.tradability_status = 'TRADABLE'
                               group by allocation.symbol
                          ) float_source
                            on float_source.symbol = instrument.symbol
                          left join stock_holding float_holding
                            on float_holding.account_id = float_source.account_id
                           and float_holding.symbol = instrument.symbol
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
        List<LiquidityProviderRecommendationResponse.Symbol> symbols = states.stream()
                .map(this::toRecommendation)
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
                REFERENCE_VOLUME_RATE,
                MIN_REFERENCE_VOLUME_RATE,
                MAX_REFERENCE_VOLUME_RATE,
                SEED_INVENTORY_RATE,
                MIN_SEED_INVENTORY_RATE,
                MAX_SEED_INVENTORY_RATE,
                BigDecimal.ONE.setScale(6),
                symbols
        );
    }

    private LiquidityProviderRecommendationResponse.Symbol toRecommendation(
            SymbolState state
    ) {
        long referenceDailyVolume = scaledQuantity(
                state.tradableShares(),
                REFERENCE_VOLUME_RATE
        );
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
