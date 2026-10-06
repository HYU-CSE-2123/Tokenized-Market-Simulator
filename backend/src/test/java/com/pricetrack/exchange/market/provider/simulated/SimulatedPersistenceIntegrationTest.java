package com.pricetrack.exchange.market.provider.simulated;

import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.model.*;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated H2 database; transaction behavior plus restart semantics, without scheduled races. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:synthetic-persistence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.price.initial-delay-ms=2147483647", "app.price.update-interval-ms=2147483647",
        "app.price.simulation.bootstrap-days=0", "app.blockchain.enabled=false", "app.blockchain.price-report.enabled=false"})
class SimulatedPersistenceIntegrationTest {
    @Autowired SyntheticMarketStateRepository states;
    @Autowired MarketCandleRepository candles;
    @Autowired SimulatedCandleProvider candleProvider;
    @Autowired PlatformTransactionManager manager;
    @MockBean MarketWebSocketPublisher events;
    final Instant start = Instant.parse("2026-10-05T15:00:00Z"); // KST midnight
    @BeforeEach void clearIsolatedTables() { states.deleteAll(); candles.deleteAll(); reset(events); }
    SimulatedPriceProvider provider(Instant at, SimulatedCandleProvider writer, int days) {
        return new SimulatedPriceProvider(states, candles, writer, events, manager, 42, days, Clock.fixed(at, ZoneOffset.UTC));
    }
    @Test void restartContinuesPersistedPriceAndPublishesOnlyAfterCommit() {
        var first = provider(start, candleProvider, 0);
        doAnswer(call -> {
            var row = states.findById("mSEC").orElseThrow();
            assertThat(row.getObservedAt()).isEqualTo(start);
            assertThat(first.current().price()).isEqualByComparingTo(row.getPrice()); return null;
        }).when(events).publishPrice(any(), any());
        first.tick(); reset(events);
        var before = states.findById("mSEC").orElseThrow().state();
        var restarted = provider(start.plusSeconds(1), candleProvider, 0); restarted.tick();
        var expected = new SyntheticMarketEngine().next(before, start.plusSeconds(1));
        assertThat(states.findById("mSEC").orElseThrow().state()).isEqualTo(expected.state());
        assertThat(restarted.current().price()).isEqualByComparingTo(expected.state().price());
        verify(events).publishPrice(restarted.current(), expected.volume());
    }
    @Test void failedCandleWriteRollsBackStateAndNeverPublishes() {
        provider(start, candleProvider, 0).tick(); reset(events);
        var before = states.findById("mSEC").orElseThrow().state();
        var writer = mock(SimulatedCandleProvider.class);
        doThrow(new IllegalStateException("fixture DB failure")).when(writer).record(any(), any(), any());
        var next = provider(start.plusSeconds(1), writer, 0);
        assertThatThrownBy(next::tick).isInstanceOf(IllegalStateException.class);
        assertThat(states.findById("mSEC").orElseThrow().state()).isEqualTo(before);
        assertThat(next.current().priceStatus()).isEqualTo(PriceStatus.INITIALIZING);
        verifyNoInteractions(events);
    }
    @Test void seedMismatchAndDuplicateClockNeverMutateCheckpoint() {
        provider(start, candleProvider, 0).tick(); reset(events);
        var before = states.findById("mSEC").orElseThrow().state();
        provider(start, candleProvider, 0).tick();
        var wrong = new SimulatedPriceProvider(states, candles, candleProvider, events, manager, 43, 0,
                Clock.fixed(start.plusSeconds(1), ZoneOffset.UTC));
        assertThatThrownBy(wrong::tick).hasMessageContaining("version/seed");
        assertThat(states.findById("mSEC").orElseThrow().state()).isEqualTo(before); verifyNoInteractions(events);
    }
    @Test void minimalBootstrapAndAllSixPeriodsUseSameQuantityAndAsOf() {
        long begin = System.nanoTime(); provider(start, candleProvider, 2).tick();
        System.out.println("synthetic bootstrap 2 days ms=" + (System.nanoTime() - begin) / 1_000_000);
        assertThat(candles.count()).isBetween(2883L, 2886L);
        for (CandleInterval interval : CandleInterval.values()) {
            var page = candleProvider.candles(interval, Math.min(5, interval.maxCount()), start);
            assertThat(page.asOf()).isEqualTo(start); assertThat(page.candles()).isNotEmpty();
            page.candles().forEach(c -> {
                assertThat(c.high()).isGreaterThanOrEqualTo(c.open()).isGreaterThanOrEqualTo(c.close());
                assertThat(c.low()).isLessThanOrEqualTo(c.open()).isLessThanOrEqualTo(c.close());
            });
        }
        var one = candleProvider.candles(CandleInterval.ONE_MINUTE, 5, start).candles();
        var daily = candleProvider.candles(CandleInterval.ONE_DAY, 1, start).candles().get(0);
        assertThat(one.get(one.size()-1).volume()).isEqualByComparingTo(daily.volume());
        verify(events, times(1)).publishPrice(any(), any());
    }
    @Test void staleAndInitializationBlockTradingWithoutFabricatingDowntimeBars() {
        var at = provider(start, candleProvider, 0); at.tick();
        long count = candles.count();
        var resumed = provider(start.plusSeconds(86400 * 10), candleProvider, 0);
        assertThatThrownBy(() -> new MarketPriceService(resumed).requireTradableSnapshot()).isInstanceOf(MarketClosedException.class);
        resumed.tick(); assertThat(candles.count()).isLessThanOrEqualTo(count + 2);
        assertThat(states.findById("mSEC").orElseThrow().getStep()).isEqualTo(2);
    }
    @Test void stoppedOrRewoundClockMakesCachedPriceUntradable() {
        class MutableClock extends Clock {
            Instant now = start;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        var clock = new MutableClock();
        var provider = new SimulatedPriceProvider(states, candles, candleProvider, events, manager, 42, 0, clock);
        provider.tick(); clock.now = start.plusSeconds(6);
        assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.STALE);
        assertThatThrownBy(() -> new MarketPriceService(provider).requireTradableSnapshot()).isInstanceOf(StalePriceException.class);
        clock.now = start.minusSeconds(3);
        assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.STALE);
        long step = states.findById("mSEC").orElseThrow().getStep(); provider.tick();
        assertThat(states.findById("mSEC").orElseThrow().getStep()).isEqualTo(step);
    }
}
