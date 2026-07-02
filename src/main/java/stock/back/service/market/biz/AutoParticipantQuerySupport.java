package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

final class AutoParticipantQuerySupport {

    private AutoParticipantQuerySupport() {
    }

    static List<String> normalizeUserKeys(List<String> userKeys) {
        if (userKeys == null || userKeys.isEmpty()) {
            return List.of();
        }
        return userKeys.stream()
                .filter(userKey -> userKey != null)
                .flatMap(userKey -> Arrays.stream(userKey.split(",")))
                .map(String::trim)
                .filter(userKey -> !userKey.isBlank())
                .distinct()
                .limit(50)
                .toList();
    }

    static BigDecimal zeroIfNull(BigDecimal value) {
        return MarketQuerySupport.zeroIfNull(value);
    }
}
