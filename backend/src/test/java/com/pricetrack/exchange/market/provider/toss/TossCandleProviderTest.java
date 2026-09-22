package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.market.model.CandleInterval;
import com.pricetrack.exchange.market.model.MarketCandle;
import com.pricetrack.exchange.market.model.MarketCandlePage;

class TossCandleProviderTest {
    @Test
    void aggregatesTossMinuteCandlesAndReturnsAnOlderBucketCursor() {
        TossMarketDataClient client = mock(TossMarketDataClient.class);
        TossPriceProperties properties = new TossPriceProperties("https://openapi.test", "id", "secret",
                "005930", Duration.ofSeconds(2), Duration.ofSeconds(5));
        Instant before = Instant.parse("2026-09-22T00:10:00Z");
        when(client.candles("005930", CandleInterval.ONE_MINUTE, 10, before))
                .thenReturn(new TossMarketDataClient.TossCandlePage(List.of(
                        candle("2026-09-22T00:00:00Z", "100", "101", "99", "100", "1"),
                        candle("2026-09-22T00:01:00Z", "100", "103", "100", "102", "2"),
                        candle("2026-09-22T00:05:00Z", "102", "105", "101", "104", "3"),
                        candle("2026-09-22T00:06:00Z", "104", "106", "103", "105", "4")), null));
        TossCandleProvider provider = new TossCandleProvider(client, properties);

        MarketCandlePage page = provider.candles(CandleInterval.FIVE_MINUTES, 1, before);

        assertThat(page.interval()).isEqualTo(CandleInterval.FIVE_MINUTES);
        assertThat(page.candles()).hasSize(1);
        assertThat(page.candles().get(0).startedAt()).isEqualTo(Instant.parse("2026-09-22T00:05:00Z"));
        assertThat(page.candles().get(0).open()).isEqualByComparingTo("102");
        assertThat(page.candles().get(0).high()).isEqualByComparingTo("106");
        assertThat(page.candles().get(0).low()).isEqualByComparingTo("101");
        assertThat(page.candles().get(0).close()).isEqualByComparingTo("105");
        assertThat(page.candles().get(0).volume()).isEqualByComparingTo("7");
        assertThat(page.nextBefore()).isEqualTo(Instant.parse("2026-09-22T00:05:00Z"));
        verify(client).candles("005930", CandleInterval.ONE_MINUTE, 10, before);
    }

    private MarketCandle candle(String startedAt, String open, String high, String low,
            String close, String volume) {
        return new MarketCandle(Instant.parse(startedAt), new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), new BigDecimal(volume), true);
    }
}
