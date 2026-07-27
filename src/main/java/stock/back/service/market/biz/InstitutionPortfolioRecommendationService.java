package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.market.vo.InstitutionPortfolioRecommendationResponse;

@Service
public class InstitutionPortfolioRecommendationService {

    private static final BigDecimal MIN_AUM_RATE = new BigDecimal("0.001000");
    private static final BigDecimal MAX_AUM_RATE = new BigDecimal("0.020000");
    private static final BigDecimal REFERENCE_VOLUME_RATE = new BigDecimal("0.030000");

    private final JdbcClient jdbcClient;

    public InstitutionPortfolioRecommendationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public InstitutionPortfolioRecommendationResponse getRecommendation() {
        List<MarketSymbol> marketSymbols = jdbcClient.sql(
                        """
                        select instrument.symbol,
                               instrument.name,
                               instrument.tradable_shares,
                               price.current_price
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                           and market.enabled = true
                           and market.market_status in ('OPEN', 'CLOSED')
                          join stock_price price
                            on price.symbol = instrument.symbol
                           and price.current_price > 0
                         where instrument.enabled = true
                           and instrument.tradable_shares > 0
                         order by instrument.symbol
                        """
                )
                .query((rs, rowNum) -> new MarketSymbol(
                        rs.getString("symbol"),
                        rs.getString("name"),
                        rs.getLong("tradable_shares"),
                        rs.getBigDecimal("current_price")
                ))
                .list();
        long currentPortfolioCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_institution_portfolio
                         where status = 'ACTIVE'
                           and execution_mode = 'LIVE'
                        """
                )
                .query(Long.class)
                .single();
        BigDecimal totalMarketCapitalization = marketSymbols.stream()
                .map(MarketSymbol::marketCapitalization)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int recommendedPortfolioCount = recommendedPortfolioCount(marketSymbols.size());
        Map<String, BigDecimal> weights = marketWeights(
                marketSymbols,
                totalMarketCapitalization
        );
        List<InstitutionPortfolioRecommendationResponse.Symbol> symbols =
                marketSymbols.stream()
                        .map(symbol -> new InstitutionPortfolioRecommendationResponse.Symbol(
                                symbol.symbol(),
                                symbol.name(),
                                symbol.tradableShares(),
                                symbol.currentPrice(),
                                weights.getOrDefault(symbol.symbol(), BigDecimal.ZERO),
                                referenceDailyVolume(symbol.tradableShares())
                        ))
                        .toList();
        List<InstitutionPortfolioRecommendationResponse.Style> styles =
                InstitutionPortfolioPolicyCatalog.policies().stream()
                        .map(policy -> new InstitutionPortfolioRecommendationResponse.Style(
                                policy.investmentStyle(),
                                policy.recommendationLabel(),
                                policy.recommendationDescription(),
                                policy.recommended(),
                                policy.recommendedAumRateOfMarketCap(),
                                recommendedAumAmount(
                                        totalMarketCapitalization,
                                        policy.recommendedAumRateOfMarketCap()
                                ),
                                policy.baseStockAllocationRate(),
                                policy.minStockAllocationRate(),
                                policy.maxStockAllocationRate(),
                                policy.primaryRegimeWeight(),
                                policy.dailyTurnoverLimitRate(),
                                policy.maxDecisionTurnoverRate(),
                                policy.decisionIntervalMinutes(),
                                policy.dailyParticipationRate()
                        ))
                        .toList();
        BigDecimal defaultAumRate = InstitutionPortfolioPolicyCatalog.recommendedPolicy()
                .recommendedAumRateOfMarketCap();
        return new InstitutionPortfolioRecommendationResponse(
                marketSymbols.size(),
                currentPortfolioCount,
                recommendedPortfolioCount,
                Math.max(0L, recommendedPortfolioCount - currentPortfolioCount),
                totalMarketCapitalization.setScale(2, RoundingMode.HALF_UP),
                defaultAumRate,
                MIN_AUM_RATE,
                MAX_AUM_RATE,
                recommendedAumAmount(totalMarketCapitalization, defaultAumRate),
                styles,
                symbols
        );
    }

    private BigDecimal recommendedAumAmount(
            BigDecimal marketCapitalization,
            BigDecimal aumRate
    ) {
        return marketCapitalization.multiply(aumRate)
                .setScale(2, RoundingMode.DOWN);
    }

    private int recommendedPortfolioCount(int activeSymbolCount) {
        if (activeSymbolCount <= 0) {
            return 0;
        }
        if (activeSymbolCount <= 2) {
            return 2;
        }
        if (activeSymbolCount <= 5) {
            return 3;
        }
        return 4;
    }

    private Map<String, BigDecimal> marketWeights(
            List<MarketSymbol> symbols,
            BigDecimal totalMarketCapitalization
    ) {
        if (symbols.isEmpty() || totalMarketCapitalization.signum() <= 0) {
            return Map.of();
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal assigned = BigDecimal.ZERO;
        for (int index = 0; index < symbols.size(); index++) {
            MarketSymbol symbol = symbols.get(index);
            BigDecimal weight = index == symbols.size() - 1
                    ? BigDecimal.ONE.subtract(assigned)
                    : symbol.marketCapitalization().divide(
                            totalMarketCapitalization,
                            8,
                            RoundingMode.HALF_UP
                    );
            result.put(symbol.symbol(), weight);
            assigned = assigned.add(weight);
        }
        return Map.copyOf(result);
    }

    private long referenceDailyVolume(long tradableShares) {
        return Math.max(
                1L,
                BigDecimal.valueOf(tradableShares)
                        .multiply(REFERENCE_VOLUME_RATE)
                        .setScale(0, RoundingMode.DOWN)
                        .longValueExact()
        );
    }

    private record MarketSymbol(
            String symbol,
            String name,
            long tradableShares,
            BigDecimal currentPrice
    ) {

        BigDecimal marketCapitalization() {
            return currentPrice.multiply(BigDecimal.valueOf(tradableShares));
        }
    }
}
