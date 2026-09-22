package com.pricetrack.exchange.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.market.model.CandleInterval;
import com.pricetrack.exchange.market.model.MarketCandle;

class MarketCandleAggregatorTest {
    @Test
    void aggregatesMinuteCandlesIntoKstAlignedFiveMinuteOhlcv() {
        List<MarketCandle> result = MarketCandleAggregator.aggregate(List.of(
                candle("2026-09-22T00:01:00Z", "100", "103", "99", "102", "10"),
                candle("2026-09-22T00:02:00Z", "102", "106", "101", "104", "20"),
                candle("2026-09-22T00:05:00Z", "105", "108", "100", "107", "30")),
                CandleInterval.FIVE_MINUTES, Instant.parse("2026-09-22T00:06:00Z"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).startedAt()).isEqualTo(Instant.parse("2026-09-22T00:00:00Z"));
        assertThat(result.get(0).open()).isEqualByComparingTo("100");
        assertThat(result.get(0).high()).isEqualByComparingTo("106");
        assertThat(result.get(0).low()).isEqualByComparingTo("99");
        assertThat(result.get(0).close()).isEqualByComparingTo("104");
        assertThat(result.get(0).volume()).isEqualByComparingTo("30");
        assertThat(result.get(0).closed()).isTrue();
        assertThat(result.get(1).startedAt()).isEqualTo(Instant.parse("2026-09-22T00:05:00Z"));
        assertThat(result.get(1).closed()).isFalse();
    }

    @Test
    void alignsAllSupportedIntradayBuckets() {
        Instant instant = Instant.parse("2026-09-22T01:47:23Z"); // KST 10:47:23

        assertThat(MarketCandleAggregator.bucket(instant, CandleInterval.FIFTEEN_MINUTES))
                .isEqualTo(Instant.parse("2026-09-22T01:45:00Z"));
        assertThat(MarketCandleAggregator.bucket(instant, CandleInterval.THIRTY_MINUTES))
                .isEqualTo(Instant.parse("2026-09-22T01:30:00Z"));
        assertThat(MarketCandleAggregator.bucket(instant, CandleInterval.ONE_HOUR))
                .isEqualTo(Instant.parse("2026-09-22T01:00:00Z"));
    }

    private MarketCandle candle(String startedAt, String open, String high, String low,
            String close, String volume) {
        return new MarketCandle(Instant.parse(startedAt), new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), new BigDecimal(volume), true);
    }
}
