package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates a safety ceiling for one automatic-participant order.
 *
 * <p>The ceiling is the lower of a KRW notional cap and a tradable-float cap.
 * It is a risk boundary, not a daily quantity target.</p>
 */
public final class AutoMarketOrderQuantityLimitPolicy {

    static final BigDecimal MAX_ORDER_NOTIONAL = new BigDecimal("5000000");
    static final BigDecimal MAX_TRADABLE_FLOAT_RATE = new BigDecimal("0.000200");

    private AutoMarketOrderQuantityLimitPolicy() {
    }

    public static int recommendedMaxOrderQuantity(
            BigDecimal referencePrice,
            long tradableShares
    ) {
        if (referencePrice == null || referencePrice.signum() <= 0) {
            throw new IllegalArgumentException("Reference price must be positive");
        }
        if (tradableShares <= 0L) {
            throw new IllegalArgumentException("Tradable shares must be positive");
        }

        BigDecimal notionalQuantity = MAX_ORDER_NOTIONAL.divide(
                referencePrice,
                0,
                RoundingMode.DOWN
        );
        BigDecimal floatQuantity = BigDecimal.valueOf(tradableShares)
                .multiply(MAX_TRADABLE_FLOAT_RATE)
                .setScale(0, RoundingMode.DOWN);
        BigDecimal recommended = notionalQuantity.min(floatQuantity)
                .max(BigDecimal.ONE)
                .min(BigDecimal.valueOf(Integer.MAX_VALUE));
        return recommended.intValueExact();
    }
}
