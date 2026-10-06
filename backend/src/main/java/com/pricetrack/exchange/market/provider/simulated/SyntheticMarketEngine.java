package com.pricetrack.exchange.market.provider.simulated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;

/** Pure, versioned synthetic process; external prices and user trades are never inputs. */
public final class SyntheticMarketEngine {
    public static final String VERSION = "synthetic-v1";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public record State(long seed, long step, BigDecimal price, BigDecimal previousClose,
                        Instant observedAt, double activity, double trend) {}
    public record Tick(State state, BigDecimal volume) {}

    public State initial(long seed, Instant time, BigDecimal price, BigDecimal previousClose) {
        return new State(seed, 0, price, previousClose, time, 0.5, 0);
    }
    public Tick next(State previous, Instant time) {
        if (!time.isAfter(previous.observedAt())) throw new IllegalArgumentException("tick time must advance");
        // Downtime is NOT replayed: take only one bounded live step after restart.
        double seconds = Math.max(0.001, Math.min(15,
                (time.toEpochMilli() - previous.observedAt().toEpochMilli()) / 1000.0));
        long step = previous.step() + 1;
        double activity = clamp(previous.activity() + (uniform(previous.seed(), step, 0) - 0.5) * 0.025, 0.1, 1.0);
        double trend = previous.trend() * Math.pow(0.995, seconds)
                + (uniform(previous.seed(), step, 1) - 0.5) * 0.00000015 * Math.sqrt(seconds);
        double gaussian = Math.sqrt(-2 * Math.log(uniform(previous.seed(), step, 2)))
                * Math.cos(2 * Math.PI * uniform(previous.seed(), step, 3));
        double delta = trend * seconds + gaussian * (0.01 + activity * 0.02) * Math.sqrt(seconds / 86400.0);
        // Demo guardrails, not Korean exchange limit rules.
        BigDecimal price = BigDecimal.valueOf(clamp(previous.price().doubleValue() * Math.exp(delta),
                1000, 1000000)).setScale(8, RoundingMode.HALF_UP);
        BigDecimal close = previous.observedAt().atZone(KST).toLocalDate()
                .equals(time.atZone(KST).toLocalDate()) ? previous.previousClose() : previous.price();
        double quantity = (0.2 + activity * 8) * -Math.log(uniform(previous.seed(), step, 4))
                * (1 + Math.min(3, Math.abs(gaussian))) * seconds;
        return new Tick(new State(previous.seed(), step, price, close, time, activity, trend),
                BigDecimal.valueOf(quantity).setScale(8, RoundingMode.HALF_UP));
    }
    private static double uniform(long seed, long step, int stream) {
        long value = seed + step * 0x9E3779B97F4A7C15L + stream * 0xD1B54A32D192ED03L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 11) + 0.5) * 0x1.0p-53;
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
