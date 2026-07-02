package stock.back.service.market.biz;

import stock.back.service.common.exception.StockException;
import web.common.core.simulation.SimulationClockSnapshot;

import java.time.LocalDateTime;
import java.util.Locale;

enum OrderBookCandleInterval {
    ONE_MINUTE("1M", 120),
    FIVE_MINUTES("5M", 120),
    FIFTEEN_MINUTES("15M", 120),
    ONE_HOUR("1H", 120),
    DAY("1D", 120),
    WEEK("1W", 120);

    private static final long SIMULATION_DAY_SECONDS = 86_400L;
    private static final long SIMULATION_WEEK_SECONDS = SIMULATION_DAY_SECONDS * 7L;

    private final String value;
    private final int limit;

    OrderBookCandleInterval(String value, int limit) {
        this.value = value;
        this.limit = limit;
    }

    static OrderBookCandleInterval parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "1M", "1MIN", "1MINUTE" -> ONE_MINUTE;
            case "5M", "5MIN", "5MINUTE" -> FIVE_MINUTES;
            case "15M", "15MIN", "15MINUTE" -> FIFTEEN_MINUTES;
            case "1H", "H", "HOUR", "1HOUR" -> ONE_HOUR;
            case "1D", "D", "DAY" -> DAY;
            case "1W", "W", "WEEK" -> WEEK;
            default -> throw StockException.badRequest("Unknown candle interval: " + value);
        };
    }

    String bucketExpression(String column) {
        if (usesSimulationClockAnchor()) {
            throw new IllegalStateException("Simulation clock bucket seconds are required for " + value);
        }
        return bucketExpression(column, 0);
    }

    String bucketExpression(String column, long simulationBucketSeconds) {
        if (usesSimulationClockAnchor()) {
            if (simulationBucketSeconds <= 0) {
                throw new IllegalArgumentException("simulationBucketSeconds must be positive for " + value);
            }
            return "timestampadd(second, floor(timestampdiff(second, ?, " + column + ") / "
                    + simulationBucketSeconds + ") * " + simulationBucketSeconds + ", ?)";
        }
        return switch (this) {
            case ONE_MINUTE -> "timestamp(date(" + column + "), maketime(hour(" + column + "), minute(" + column + "), 0))";
            case FIVE_MINUTES -> "timestamp(date(" + column + "), maketime(hour(" + column + "), floor(minute(" + column + ") / 5) * 5, 0))";
            case FIFTEEN_MINUTES -> "timestamp(date(" + column + "), maketime(hour(" + column + "), floor(minute(" + column + ") / 15) * 15, 0))";
            case ONE_HOUR -> "timestamp(date(" + column + "), maketime(hour(" + column + "), 0, 0))";
            case DAY, WEEK -> throw new IllegalStateException("Simulation clock bucket seconds are required for " + value);
        };
    }

    LocalDateTime floor(LocalDateTime value) {
        return switch (this) {
            case ONE_MINUTE -> value.withSecond(0).withNano(0);
            case FIVE_MINUTES -> value.withMinute((value.getMinute() / 5) * 5).withSecond(0).withNano(0);
            case FIFTEEN_MINUTES -> value.withMinute((value.getMinute() / 15) * 15).withSecond(0).withNano(0);
            case ONE_HOUR -> value.withMinute(0).withSecond(0).withNano(0);
            case DAY, WEEK -> throw new IllegalStateException("Simulation clock is required for " + value);
        };
    }

    LocalDateTime floor(LocalDateTime value, SimulationClockSnapshot clock) {
        if (this == DAY) {
            return clock.simulationDayStart();
        }
        if (this == WEEK) {
            long daysSinceWeekStart = clock.simulationDate().getDayOfWeek().getValue() - 1L;
            return clock.simulationDayStart().minusDays(daysSinceWeekStart);
        }
        return floor(value);
    }

    LocalDateTime minus(LocalDateTime value, int intervals) {
        return switch (this) {
            case ONE_MINUTE -> value.minusMinutes(intervals);
            case FIVE_MINUTES -> value.minusMinutes(intervals * 5L);
            case FIFTEEN_MINUTES -> value.minusMinutes(intervals * 15L);
            case ONE_HOUR -> value.minusHours(intervals);
            case DAY, WEEK -> throw new IllegalStateException("Simulation clock is required for " + value);
        };
    }

    LocalDateTime minus(LocalDateTime value, int intervals, SimulationClockSnapshot clock) {
        if (this == DAY) {
            return value.minusDays(intervals);
        }
        if (this == WEEK) {
            return value.minusWeeks(intervals);
        }
        return minus(value, intervals);
    }

    LocalDateTime next(LocalDateTime value) {
        return switch (this) {
            case ONE_MINUTE -> value.plusMinutes(1);
            case FIVE_MINUTES -> value.plusMinutes(5);
            case FIFTEEN_MINUTES -> value.plusMinutes(15);
            case ONE_HOUR -> value.plusHours(1);
            case DAY, WEEK -> throw new IllegalStateException("Simulation clock is required for " + value);
        };
    }

    LocalDateTime next(LocalDateTime value, SimulationClockSnapshot clock) {
        if (this == DAY) {
            return value.plusDays(1);
        }
        if (this == WEEK) {
            return value.plusWeeks(1);
        }
        return next(value);
    }

    boolean usesSimulationClockAnchor() {
        return this == DAY || this == WEEK;
    }

    long simulationBucketSeconds(SimulationClockSnapshot clock) {
        if (this == DAY) {
            return SIMULATION_DAY_SECONDS;
        }
        if (this == WEEK) {
            return SIMULATION_WEEK_SECONDS;
        }
        throw new IllegalStateException("No simulation bucket seconds for " + value);
    }

    String value() {
        return value;
    }

    int limit() {
        return limit;
    }
}
