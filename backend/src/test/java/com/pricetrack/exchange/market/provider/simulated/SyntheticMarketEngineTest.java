package com.pricetrack.exchange.market.provider.simulated;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SyntheticMarketEngineTest {
    private final SyntheticMarketEngine engine = new SyntheticMarketEngine();
    private final Instant start = Instant.parse("2026-10-05T14:59:59Z");
    @Test void sameSeedAndClockProduceIdenticalTicksAndRestartContinuation() {
        var state = engine.initial(42, start, new BigDecimal("75000"), new BigDecimal("74000"));
        var a = engine.next(state, start.plusSeconds(1));
        assertThat(engine.next(state, start.plusSeconds(1))).isEqualTo(a);
        assertThat(engine.next(new SyntheticMarketState("mSEC", a.state()).state(), start.plusSeconds(2)))
                .isEqualTo(engine.next(a.state(), start.plusSeconds(2)));
    }
    @Test void midnightChangesPreviousCloseOnlyOnce() {
        var state = engine.initial(42, start, new BigDecimal("75000"), new BigDecimal("74000"));
        var a = engine.next(state, start.plusSeconds(1));
        assertThat(a.state().previousClose()).isEqualByComparingTo("75000");
        assertThat(engine.next(a.state(), start.plusSeconds(2)).state().previousClose()).isEqualByComparingTo("75000");
    }
    @Test void activityAndQuantityVaryWithFiniteBoundedPrices() {
        var state = engine.initial(42, start, new BigDecimal("75000"), new BigDecimal("74000"));
        var volumes = new HashSet<BigDecimal>(); var activities = new HashSet<Double>();
        for (int i = 1; i <= 10000; i++) {
            var tick = engine.next(state, start.plusSeconds(i)); state = tick.state();
            assertThat(state.price()).isBetween(new BigDecimal("1000"), new BigDecimal("1000000"));
            assertThat(tick.volume()).isNotNegative(); volumes.add(tick.volume()); activities.add(state.activity());
        }
        assertThat(volumes.size()).isGreaterThan(9900); assertThat(activities.size()).isGreaterThan(100);
    }
    @Test void downtimeIsOneBoundedStepNotCatchUpAndClockRollbackIsRejected() {
        var state = engine.initial(42, start, new BigDecimal("75000"), new BigDecimal("74000"));
        assertThat(engine.next(state, start.plusSeconds(864000)).state().step()).isEqualTo(1);
        assertThatThrownBy(() -> engine.next(state, start)).isInstanceOf(IllegalArgumentException.class);
    }
}
