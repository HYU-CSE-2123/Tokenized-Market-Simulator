package com.pricetrack.exchange.market.provider.simulated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.model.*;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;

/** Persistent synthetic market. Quotes and clients see committed ticks only. */
@Component
@ConditionalOnProperty(name = "app.price.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedPriceProvider implements MarketPriceProvider {
    private static final BigDecimal INITIAL_PRICE = new BigDecimal("75000");
    private final SyntheticMarketEngine engine = new SyntheticMarketEngine();
    private final SyntheticMarketStateRepository states;
    private final MarketCandleRepository candles;
    private final SimulatedCandleProvider candleProvider;
    private final MarketWebSocketPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final long seed;
    private final int bootstrapDays;
    private final AtomicReference<MarketPriceSnapshot> current = new AtomicReference<>();

    @Autowired
    public SimulatedPriceProvider(SyntheticMarketStateRepository states, MarketCandleRepository candles,
            SimulatedCandleProvider candleProvider, MarketWebSocketPublisher events,
            PlatformTransactionManager manager, @Value("${app.price.simulation.seed:20261006}") long seed,
            @Value("${app.price.simulation.bootstrap-days:0}") int bootstrapDays) {
        this(states, candles, candleProvider, events, manager, seed, bootstrapDays, Clock.systemUTC());
    }
    SimulatedPriceProvider(SyntheticMarketStateRepository states, MarketCandleRepository candles,
            SimulatedCandleProvider candleProvider, MarketWebSocketPublisher events,
            PlatformTransactionManager manager, long seed, int bootstrapDays, Clock clock) {
        if (bootstrapDays < 0 || bootstrapDays > 2) throw new IllegalArgumentException("bootstrap-days must be 0..2");
        this.states = states; this.candles = candles; this.candleProvider = candleProvider;
        this.events = events; this.seed = seed; this.bootstrapDays = bootstrapDays; this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override
    public MarketPriceSnapshot current() {
        MarketPriceSnapshot value = current.get();
        if (value == null) return new MarketPriceSnapshot("mSEC", INITIAL_PRICE, INITIAL_PRICE,
                BigDecimal.ZERO, BigDecimal.ZERO, MarketStatus.CLOSED, PriceStatus.INITIALIZING,
                "SIMULATED", Instant.EPOCH);
        Duration age = Duration.between(value.observedAt(), clock.instant());
        if (age.compareTo(Duration.ofSeconds(5)) > 0 || age.compareTo(Duration.ofSeconds(-2)) < 0)
            return new MarketPriceSnapshot(value.symbol(), value.price(), value.previousClose(), value.change(),
                    value.changeRate(), MarketStatus.OPEN, PriceStatus.STALE, "SIMULATED", value.observedAt());
        return value;
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public synchronized void start() { advance(false); }

    @Scheduled(fixedDelayString = "${app.price.update-interval-ms:1000}",
            initialDelayString = "${app.price.initial-delay-ms:1000}")
    public synchronized void tick() {
        advance(true);
    }
    private void advance(boolean publish) {
        SyntheticMarketEngine.Tick tick = transaction.execute(status -> {
            // Millisecond precision matches browser Date, making timestamp-only dedup unambiguous.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            SyntheticMarketState row = states.lock("mSEC").orElseGet(() -> initialize(now));
            if (!SyntheticMarketEngine.VERSION.equals(row.getEngineVersion()) || row.getSeed() != seed)
                throw new IllegalStateException("synthetic engine version/seed differs from persisted state");
            if (!now.isAfter(row.getObservedAt())) return null;
            var next = engine.next(row.state(), now);
            candleProvider.record(next.state().price(), next.volume(), now);
            row.apply(next.state()); states.saveAndFlush(row);
            return next;
        });
        if (tick == null) return;
        var state = tick.state();
        BigDecimal change = state.price().subtract(state.previousClose());
        var snapshot = new MarketPriceSnapshot("mSEC", state.price(), state.previousClose(), change,
                change.divide(state.previousClose(), 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)),
                MarketStatus.OPEN, PriceStatus.SIMULATED, "SIMULATED", state.observedAt());
        current.set(snapshot); // transaction has committed; rollback cannot reach here
        if (publish) events.publishPrice(snapshot, tick.volume());
    }
    private SyntheticMarketState initialize(Instant now) {
        var latest = candles.findBySymbolAndIntervalAndStartedAtLessThanEqualOrderByStartedAtDesc(
                "mSEC", CandleInterval.ONE_MINUTE, now, org.springframework.data.domain.PageRequest.of(0, 1));
        SyntheticMarketEngine.State state;
        if (!latest.isEmpty()) {
            // Adopt legacy synthetic history without overwriting it or resetting any balance.
            BigDecimal price = latest.get(0).getClose();
            Instant day = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            var daily = candles.findBySymbolAndIntervalAndStartedAtLessThanEqualOrderByStartedAtDesc(
                    "mSEC", CandleInterval.ONE_DAY, day.minusNanos(1), org.springframework.data.domain.PageRequest.of(0, 1));
            state = engine.initial(seed, now.minusSeconds(1), price, daily.isEmpty() ? price : daily.get(0).getClose());
        } else {
            Instant start = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
                    .minusDays(bootstrapDays).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            if (bootstrapDays == 0) start = now.minusSeconds(1);
            state = engine.initial(seed, start.minusSeconds(15), INITIAL_PRICE, INITIAL_PRICE);
            java.util.Map<String, MarketCandleEntity> history = new java.util.LinkedHashMap<>();
            // Two-day maximum prehistory, same engine at 15s resolution. Never emit past WS/quotes.
            for (Instant at = start; at.isBefore(now.minusSeconds(1)); at = at.plusSeconds(15)) {
                var tick = engine.next(state, at); state = tick.state();
                for (CandleInterval interval : java.util.List.of(CandleInterval.ONE_MINUTE, CandleInterval.ONE_DAY)) {
                    Instant bucket = SimulatedCandleProvider.bucket(at, interval);
                    String key = interval + ":" + bucket;
                    BigDecimal price = state.price();
                    MarketCandleEntity candle = history.computeIfAbsent(key, ignored -> {
                        var value = new MarketCandleEntity(); value.setSymbol("mSEC"); value.setInterval(interval);
                        value.setStartedAt(bucket); value.setOpen(price); value.setHigh(price);
                        value.setLow(price); value.setClose(price); value.setVolume(BigDecimal.ZERO); return value;
                    });
                    candle.setHigh(candle.getHigh().max(price)); candle.setLow(candle.getLow().min(price));
                    candle.setClose(price); candle.setVolume(candle.getVolume().add(tick.volume()));
                }
            }
            candles.saveAll(history.values());
        }
        return states.save(new SyntheticMarketState("mSEC", state));
    }
}
