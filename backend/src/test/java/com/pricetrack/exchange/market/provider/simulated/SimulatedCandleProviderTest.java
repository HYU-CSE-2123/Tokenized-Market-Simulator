package com.pricetrack.exchange.market.provider.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.pricetrack.exchange.market.MarketCandleEntity;
import com.pricetrack.exchange.market.MarketCandleRepository;
import com.pricetrack.exchange.market.model.CandleInterval;
import com.pricetrack.exchange.market.model.MarketCandlePage;

class SimulatedCandleProviderTest {
    @Test
    void updatesMinuteAndDailyOhlcvWithoutCreatingRawTicks() {
        MarketCandleRepository repository = mock(MarketCandleRepository.class);
        Map<String, MarketCandleEntity> stored = new HashMap<>();
        when(repository.findBySymbolAndIntervalAndStartedAt(any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(key(
                        invocation.getArgument(1), invocation.getArgument(2)))));
        when(repository.save(any())).thenAnswer(invocation -> {
            MarketCandleEntity entity = invocation.getArgument(0);
            stored.put(key(entity.getInterval(), entity.getStartedAt()), entity);
            return entity;
        });
        SimulatedCandleProvider provider = new SimulatedCandleProvider(repository);
        Instant first = Instant.parse("2026-09-16T00:00:10Z");

        provider.record(new BigDecimal("100"), first);
        provider.record(new BigDecimal("103"), first.plusSeconds(10));
        provider.record(new BigDecimal("99"), first.plusSeconds(20));

        MarketCandleEntity minute = stored.get(key(CandleInterval.ONE_MINUTE,
                Instant.parse("2026-09-16T00:00:00Z")));
        assertThat(minute.getOpen()).isEqualByComparingTo("100");
        assertThat(minute.getHigh()).isEqualByComparingTo("103");
        assertThat(minute.getLow()).isEqualByComparingTo("99");
        assertThat(minute.getClose()).isEqualByComparingTo("99");
        assertThat(minute.getVolume()).isEqualByComparingTo("3");
        assertThat(stored).hasSize(2);
    }

    @Test
    void returnsCandlesChronologicallyAndUsesTheExtraRowAsNextCursor() {
        MarketCandleRepository repository = mock(MarketCandleRepository.class);
        Instant newest = Instant.parse("2026-09-16T00:02:00Z");
        Instant middle = newest.minusSeconds(60);
        Instant oldest = newest.minusSeconds(120);
        when(repository.findBySymbolAndIntervalAndStartedAtLessThanEqualOrderByStartedAtDesc(
                any(), any(), any(), any(Pageable.class)))
                .thenReturn(java.util.List.of(candle(newest, "102"), candle(middle, "101"), candle(oldest, "100")));
        SimulatedCandleProvider provider = new SimulatedCandleProvider(repository);

        MarketCandlePage page = provider.candles(CandleInterval.ONE_MINUTE, 2, newest);

        assertThat(page.candles()).extracting(candle -> candle.startedAt())
                .containsExactly(middle, newest);
        assertThat(page.nextBefore()).isEqualTo(oldest);
    }

    @Test
    void aggregatesStoredMinuteCandlesForFiveMinuteRequests() {
        MarketCandleRepository repository = mock(MarketCandleRepository.class);
        when(repository.findBySymbolAndIntervalAndStartedAtLessThanEqualOrderByStartedAtDesc(
                any(), any(), any(), any(Pageable.class)))
                .thenReturn(java.util.List.of(
                        candle(Instant.parse("2026-09-16T00:06:00Z"), "106"),
                        candle(Instant.parse("2026-09-16T00:05:00Z"), "105"),
                        candle(Instant.parse("2026-09-16T00:01:00Z"), "101"),
                        candle(Instant.parse("2026-09-16T00:00:00Z"), "100")));
        SimulatedCandleProvider provider = new SimulatedCandleProvider(repository);

        MarketCandlePage page = provider.candles(CandleInterval.FIVE_MINUTES, 1,
                Instant.parse("2026-09-16T00:10:00Z"));

        assertThat(page.candles()).hasSize(1);
        assertThat(page.candles().get(0).startedAt()).isEqualTo(Instant.parse("2026-09-16T00:05:00Z"));
        assertThat(page.candles().get(0).open()).isEqualByComparingTo("105");
        assertThat(page.candles().get(0).close()).isEqualByComparingTo("106");
        assertThat(page.candles().get(0).volume()).isEqualByComparingTo("2");
        assertThat(page.nextBefore()).isEqualTo(Instant.parse("2026-09-16T00:04:00Z"));
    }

    private MarketCandleEntity candle(Instant startedAt, String price) {
        MarketCandleEntity candle = new MarketCandleEntity();
        candle.setSymbol("mSEC");
        candle.setInterval(CandleInterval.ONE_MINUTE);
        candle.setStartedAt(startedAt);
        candle.setOpen(new BigDecimal(price));
        candle.setHigh(new BigDecimal(price));
        candle.setLow(new BigDecimal(price));
        candle.setClose(new BigDecimal(price));
        candle.setVolume(BigDecimal.ONE);
        return candle;
    }

    private String key(CandleInterval interval, Instant startedAt) {
        return interval + ":" + startedAt;
    }
}
