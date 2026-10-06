package com.pricetrack.exchange.market.provider.simulated;

import java.math.BigDecimal;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Single-market checkpoint committed atomically with synthetic candles. */
@Entity @Table(name = "synthetic_market_state") @Getter @NoArgsConstructor
public class SyntheticMarketState {
    @Id private String symbol;
    @Column(nullable = false) private String engineVersion;
    @Column(nullable = false) private long seed;
    @Column(nullable = false) private long step;
    @Column(nullable = false, precision = 30, scale = 8) private BigDecimal price;
    @Column(nullable = false, precision = 30, scale = 8) private BigDecimal previousClose;
    @Column(nullable = false) private Instant observedAt;
    @Column(nullable = false) private double activity;
    @Column(nullable = false) private double trend;
    public SyntheticMarketState(String symbol, SyntheticMarketEngine.State state) {
        this.symbol = symbol; this.engineVersion = SyntheticMarketEngine.VERSION; apply(state);
    }
    public SyntheticMarketEngine.State state() {
        return new SyntheticMarketEngine.State(seed, step, price, previousClose, observedAt, activity, trend);
    }
    public void apply(SyntheticMarketEngine.State state) {
        seed = state.seed(); step = state.step(); price = state.price(); previousClose = state.previousClose();
        observedAt = state.observedAt(); activity = state.activity(); trend = state.trend();
    }
}
