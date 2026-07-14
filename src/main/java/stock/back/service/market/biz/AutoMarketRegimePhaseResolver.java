package stock.back.service.market.biz;

import java.time.LocalDateTime;
import java.time.LocalTime;

final class AutoMarketRegimePhaseResolver {

    private AutoMarketRegimePhaseResolver() {
    }

    static String resolve(LocalDateTime dateTime) {
        LocalTime time = dateTime == null ? null : dateTime.toLocalTime();
        if (time == null || time.isBefore(LocalTime.of(9, 0))) {
            return "SLOT_0600";
        }
        if (time.isBefore(LocalTime.NOON)) {
            return "SLOT_0900";
        }
        if (time.isBefore(LocalTime.of(15, 0))) {
            return "SLOT_1200";
        }
        return "SLOT_1500";
    }
}
