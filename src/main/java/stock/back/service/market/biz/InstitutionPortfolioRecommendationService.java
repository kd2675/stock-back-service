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
    private static final BigDecimal MAX_AUM_RATE = new BigDecimal("0.100000");
    private final JdbcClient jdbcClient;
    private final MarketReferenceVolumeResolver referenceVolumeResolver;

    public InstitutionPortfolioRecommendationService(
            JdbcClient jdbcClient,
            MarketReferenceVolumeResolver referenceVolumeResolver
    ) {
        this.jdbcClient = jdbcClient;
        this.referenceVolumeResolver = referenceVolumeResolver;
    }

    @Transactional(readOnly = true)
    public InstitutionPortfolioRecommendationResponse getRecommendation() {
        List<MarketSymbol> marketSymbols = jdbcClient.sql(
                        """
                        select instrument.symbol,
                               instrument.name,
                               instrument.tradable_shares,
                               price.current_price,
                               market.enabled as market_enabled
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                           and (
                               market.market_status = 'CLOSED'
                               or (
                                   market.enabled = true
                                   and market.market_status = 'OPEN'
                               )
                           )
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
                        rs.getBigDecimal("current_price"),
                        rs.getBoolean("market_enabled")
                ))
                .list();
        Map<String, MarketReferenceVolumeResolver.Resolution> referenceVolumes =
                referenceVolumeResolver.resolve(marketSymbols.stream()
                        .map(symbol -> new MarketReferenceVolumeResolver.SymbolFloat(
                                symbol.symbol(),
                                symbol.tradableShares()
                        ))
                        .toList());
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
                                referenceVolumes.get(symbol.symbol()).referenceDailyVolume(),
                                referenceVolumes.get(symbol.symbol())
                                        .referenceDailyVolumeRate(),
                                referenceVolumes.get(symbol.symbol()).completedHistoryDays(),
                                referenceVolumes.get(symbol.symbol()).source(),
                                symbol.marketEnabled()
                                        ? "ACTIVE"
                                        : "PENDING_MARKET_ACTIVATION"
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
                                policy.assetPreferenceSensitivity(),
                                policy.volatilitySensitivity(),
                                policy.entryThresholdRate(),
                                policy.exitThresholdRate(),
                                policy.dailyTurnoverLimitRate(),
                                policy.maxDecisionTurnoverRate(),
                                policy.decisionIntervalMinutes(),
                                policy.pricePressureSensitivity(),
                                policy.momentumSensitivity(),
                                policy.valueSensitivity(),
                                policy.reportSensitivity(),
                                policy.dailyParticipationRate()
                        ))
                        .toList();
        BigDecimal defaultAumRate = InstitutionPortfolioPolicyCatalog.recommendedPolicy()
                .recommendedAumRateOfMarketCap();
        int activeSymbolCount = (int) marketSymbols.stream()
                .filter(MarketSymbol::marketEnabled)
                .count();
        return new InstitutionPortfolioRecommendationResponse(
                activeSymbolCount,
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

    private int recommendedPortfolioCount(int eligibleSymbolCount) {
        if (eligibleSymbolCount <= 0) {
            return 0;
        }
        if (eligibleSymbolCount <= 2) {
            return 2;
        }
        if (eligibleSymbolCount <= 5) {
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

    private record MarketSymbol(
            String symbol,
            String name,
            long tradableShares,
            BigDecimal currentPrice,
            boolean marketEnabled
    ) {

        BigDecimal marketCapitalization() {
            return currentPrice.multiply(BigDecimal.valueOf(tradableShares));
        }
    }
}
