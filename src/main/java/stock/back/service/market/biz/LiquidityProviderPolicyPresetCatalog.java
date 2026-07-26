package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class LiquidityProviderPolicyPresetCatalog {

    public static final String STABLE = "STABLE";
    public static final String BALANCED = "BALANCED";
    public static final String ACTIVE = "ACTIVE";

    private static final BigDecimal DEFAULT_PRIMARY_REGIME_WEIGHT =
            new BigDecimal("0.700000");
    private static final BigDecimal DEFAULT_LIQUIDITY_SIZE_SENSITIVITY =
            new BigDecimal("0.250000");

    private static final List<PresetDefinition> DEFINITIONS = List.of(
            new PresetDefinition(
                    STABLE,
                    false,
                    new BigDecimal("0.020000"),
                    new BigDecimal("0.005000"),
                    new BigDecimal("0.015000"),
                    new BigDecimal("0.005000"),
                    new BigDecimal("0.050000"),
                    new BigDecimal("0.080000"),
                    new BigDecimal("6.0000"),
                    new BigDecimal("0.002500"),
                    new BigDecimal("0.020000"),
                    6,
                    16,
                    4,
                    6,
                    0,
                    1_200,
                    4,
                    3_600,
                    600
            ),
            new PresetDefinition(
                    BALANCED,
                    true,
                    new BigDecimal("0.030000"),
                    new BigDecimal("0.007500"),
                    new BigDecimal("0.025000"),
                    new BigDecimal("0.007500"),
                    new BigDecimal("0.100000"),
                    new BigDecimal("0.180000"),
                    new BigDecimal("5.0000"),
                    new BigDecimal("0.005000"),
                    new BigDecimal("0.040000"),
                    4,
                    12,
                    3,
                    4,
                    1,
                    600,
                    3,
                    1_800,
                    300
            ),
            new PresetDefinition(
                    ACTIVE,
                    false,
                    new BigDecimal("0.050000"),
                    new BigDecimal("0.010000"),
                    new BigDecimal("0.040000"),
                    new BigDecimal("0.010000"),
                    new BigDecimal("0.150000"),
                    new BigDecimal("0.300000"),
                    new BigDecimal("4.0000"),
                    new BigDecimal("0.010000"),
                    new BigDecimal("0.060000"),
                    3,
                    10,
                    2,
                    3,
                    1,
                    300,
                    2,
                    1_200,
                    180
            )
    );

    public List<ResolvedPreset> resolveAll(
            long tradableShares,
            long targetInventoryQuantity,
            BigDecimal currentNetAssetValue,
            BigDecimal primaryRegimeWeight,
            BigDecimal liquiditySizeSensitivity
    ) {
        return DEFINITIONS.stream()
                .map(definition -> resolve(
                        definition,
                        tradableShares,
                        null,
                        targetInventoryQuantity,
                        currentNetAssetValue,
                        primaryRegimeWeight,
                        liquiditySizeSensitivity
                ))
                .toList();
    }

    public ResolvedPreset resolveBalancedForReferenceVolume(
            long tradableShares,
            long referenceDailyVolume,
            long targetInventoryQuantity,
            BigDecimal currentNetAssetValue
    ) {
        return resolve(
                definition(BALANCED),
                tradableShares,
                referenceDailyVolume,
                targetInventoryQuantity,
                currentNetAssetValue,
                DEFAULT_PRIMARY_REGIME_WEIGHT,
                DEFAULT_LIQUIDITY_SIZE_SENSITIVITY
        );
    }

    private ResolvedPreset resolve(
            PresetDefinition definition,
            long tradableShares,
            Long referenceDailyVolumeOverride,
            long targetInventoryQuantity,
            BigDecimal currentNetAssetValue,
            BigDecimal primaryRegimeWeight,
            BigDecimal liquiditySizeSensitivity
    ) {
        if (tradableShares <= 0L) {
            throw new IllegalArgumentException("Tradable shares must be positive");
        }
        if (targetInventoryQuantity < 0L || targetInventoryQuantity >= tradableShares) {
            throw new IllegalArgumentException(
                    "Target inventory must leave room for a positive inventory band"
            );
        }
        long referenceDailyVolume = referenceDailyVolumeOverride == null
                ? scaledQuantity(tradableShares, definition.referenceDailyVolumeFloatRate())
                : referenceDailyVolumeOverride;
        if (referenceDailyVolume <= 0L) {
            throw new IllegalArgumentException("Reference daily volume must be positive");
        }
        long availableInventoryBand = tradableShares - targetInventoryQuantity;
        long inventoryBandQuantity = Math.min(
                availableInventoryBand,
                scaledQuantity(tradableShares, definition.inventoryBandFloatRate())
        );
        long maxOrderQuantity = Math.min(
                inventoryBandQuantity,
                scaledQuantity(
                        referenceDailyVolume,
                        definition.maxSingleOrderParticipationRate()
                )
        );
        BigDecimal netAssetValue = currentNetAssetValue == null
                ? BigDecimal.ZERO
                : currentNetAssetValue.max(BigDecimal.ZERO);
        BigDecimal dailyLossLimitAmount = netAssetValue
                .multiply(definition.dailyLossNetAssetRate())
                .max(BigDecimal.ONE)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal normalizedPrimaryWeight = normalizedRate(
                primaryRegimeWeight,
                DEFAULT_PRIMARY_REGIME_WEIGHT
        );
        BigDecimal normalizedLiquiditySensitivity = normalizedRate(
                liquiditySizeSensitivity,
                DEFAULT_LIQUIDITY_SIZE_SENSITIVITY
        );

        ResolvedPolicy policy = new ResolvedPolicy(
                definition.targetSpreadTicks(),
                definition.maxSpreadTicks(),
                maxOrderQuantity,
                referenceDailyVolume,
                definition.targetOpenParticipationRate(),
                definition.maxOpenParticipationRate(),
                definition.maxSingleOrderParticipationRate(),
                5,
                definition.maxExternalDepthParticipationRate(),
                definition.dailyExecutionParticipationRate(),
                definition.dailySubmissionMultiplier(),
                targetInventoryQuantity,
                inventoryBandQuantity,
                definition.inventorySkewTicks(),
                normalizedPrimaryWeight,
                normalizedLiquiditySensitivity,
                definition.volatilitySpreadMaxTicks(),
                definition.priceRegimeMaxSkewTicks(),
                true,
                definition.minimumQuoteLifetimeSeconds(),
                definition.repriceThresholdTicks(),
                definition.orderTtlSeconds(),
                definition.quoteIntervalSeconds(),
                dailyLossLimitAmount
        );
        long executionQuantityLimit = scaledQuantity(
                referenceDailyVolume,
                definition.dailyExecutionParticipationRate()
        );
        long submissionQuantityLimit = BigDecimal.valueOf(executionQuantityLimit)
                .multiply(definition.dailySubmissionMultiplier())
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
        return new ResolvedPreset(
                definition.code(),
                definition.recommended(),
                ratio(referenceDailyVolume, tradableShares),
                ratio(maxOrderQuantity, tradableShares),
                ratio(executionQuantityLimit, tradableShares),
                ratio(submissionQuantityLimit, tradableShares),
                ratio(inventoryBandQuantity, tradableShares),
                definition.dailyLossNetAssetRate(),
                policy
        );
    }

    private PresetDefinition definition(String code) {
        return DEFINITIONS.stream()
                .filter(definition -> definition.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown liquidity-provider policy preset: " + code
                ));
    }

    private long scaledQuantity(long baseQuantity, BigDecimal rate) {
        return Math.max(
                1L,
                BigDecimal.valueOf(baseQuantity)
                        .multiply(rate)
                        .setScale(0, RoundingMode.CEILING)
                        .longValueExact()
        );
    }

    private BigDecimal ratio(long quantity, long baseQuantity) {
        return BigDecimal.valueOf(quantity)
                .divide(BigDecimal.valueOf(baseQuantity), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizedRate(BigDecimal value, BigDecimal fallback) {
        BigDecimal resolved = value == null ? fallback : value;
        return resolved.setScale(6, RoundingMode.HALF_UP);
    }

    private record PresetDefinition(
            String code,
            boolean recommended,
            BigDecimal referenceDailyVolumeFloatRate,
            BigDecimal targetOpenParticipationRate,
            BigDecimal maxOpenParticipationRate,
            BigDecimal maxSingleOrderParticipationRate,
            BigDecimal maxExternalDepthParticipationRate,
            BigDecimal dailyExecutionParticipationRate,
            BigDecimal dailySubmissionMultiplier,
            BigDecimal inventoryBandFloatRate,
            BigDecimal dailyLossNetAssetRate,
            int targetSpreadTicks,
            int maxSpreadTicks,
            int inventorySkewTicks,
            int volatilitySpreadMaxTicks,
            int priceRegimeMaxSkewTicks,
            int minimumQuoteLifetimeSeconds,
            int repriceThresholdTicks,
            int orderTtlSeconds,
            int quoteIntervalSeconds
    ) {
    }

    public record ResolvedPreset(
            String code,
            boolean recommended,
            BigDecimal referenceDailyVolumeFloatRate,
            BigDecimal oneSideQuoteFloatRate,
            BigDecimal dailyExecutionFloatRate,
            BigDecimal dailySubmissionFloatRate,
            BigDecimal inventoryBandFloatRate,
            BigDecimal dailyLossNetAssetRate,
            ResolvedPolicy policy
    ) {
    }

    public record ResolvedPolicy(
            int targetSpreadTicks,
            int maxSpreadTicks,
            long maxOrderQuantity,
            long referenceDailyVolume,
            BigDecimal targetOpenParticipationRate,
            BigDecimal maxOpenParticipationRate,
            BigDecimal maxSingleOrderParticipationRate,
            int externalDepthLevels,
            BigDecimal maxExternalDepthParticipationRate,
            BigDecimal dailyExecutionParticipationRate,
            BigDecimal dailySubmissionMultiplier,
            long targetInventoryQuantity,
            long inventoryBandQuantity,
            int inventorySkewTicks,
            BigDecimal primaryRegimeWeight,
            BigDecimal liquiditySizeSensitivity,
            int volatilitySpreadMaxTicks,
            int priceRegimeMaxSkewTicks,
            boolean passiveOnly,
            int minimumQuoteLifetimeSeconds,
            int repriceThresholdTicks,
            int orderTtlSeconds,
            int quoteIntervalSeconds,
            BigDecimal dailyLossLimitAmount
    ) {
    }
}
