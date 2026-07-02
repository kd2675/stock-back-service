package stock.back.service.market.biz;

import java.util.Locale;

final class MarketTextNormalizer {

    private MarketTextNormalizer() {
    }

    static String symbol(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    static String optionalText(String value) {
        String normalized = text(value);
        return normalized.isBlank() ? null : normalized;
    }
}
