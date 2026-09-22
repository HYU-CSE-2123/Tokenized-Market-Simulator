package com.pricetrack.exchange.market;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.common.exception.UnsupportedSymbolException;
import com.pricetrack.exchange.market.provider.MarketCandleProvider;
import com.pricetrack.exchange.market.model.CandleInterval;

class MarketCandleServiceTest {
    private final MarketCandleProvider provider = mock(MarketCandleProvider.class);
    private final MarketCandleService service = new MarketCandleService(provider);

    @Test
    void rejectsUnsupportedSymbolIntervalAndCount() {
        assertThatThrownBy(() -> service.candles("OTHER", "1m", 10, null))
                .isInstanceOf(UnsupportedSymbolException.class);
        assertThatThrownBy(() -> service.candles("mSEC", "2m", 10, null))
                .isInstanceOf(InvalidCandleQueryException.class);
        assertThatThrownBy(() -> service.candles("mSEC", "1m", 201, null))
                .isInstanceOf(InvalidCandleQueryException.class);
        assertThatThrownBy(() -> service.candles("mSEC", "1h", 21, null))
                .isInstanceOf(InvalidCandleQueryException.class);
    }

    @Test
    void acceptsAggregatedIntervalsWithinTheirLimits() {
        service.candles("mSEC", "5m", 100, Instant.parse("2026-09-22T00:00:00Z"));

        verify(provider).candles(CandleInterval.FIVE_MINUTES, 100,
                Instant.parse("2026-09-22T00:00:00Z"));
    }

    @Test
    void usesTheIntervalSpecificMaximumAsTheDefaultWhenItIsBelowOneHundred() {
        Instant before = Instant.parse("2026-09-22T00:00:00Z");

        service.candles("mSEC", "1h", null, before);

        verify(provider).candles(CandleInterval.ONE_HOUR, 20, before);
    }
}
