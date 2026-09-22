package com.pricetrack.exchange.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pricetrack.exchange.market.model.CandleInterval;
import com.pricetrack.exchange.market.model.MarketCandle;

/** 1분봉을 한국 시장의 벽시계 구간에 맞춘 상위 OHLCV 봉으로 집계한다. */
public final class MarketCandleAggregator {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private MarketCandleAggregator() {}

    public static List<MarketCandle> aggregate(List<MarketCandle> minuteCandles,
            CandleInterval target, Instant now) {
        if (!target.isAggregatedIntraday()) {
            throw new IllegalArgumentException("상위 분봉 주기만 집계할 수 있습니다.");
        }
        Map<Instant, MutableCandle> buckets = new LinkedHashMap<>();
        minuteCandles.stream().sorted(Comparator.comparing(MarketCandle::startedAt)).forEach(candle -> {
            Instant startedAt = bucket(candle.startedAt(), target);
            buckets.computeIfAbsent(startedAt, ignored -> new MutableCandle(startedAt, candle))
                    .add(candle);
        });
        List<MarketCandle> result = new ArrayList<>();
        buckets.values().forEach(bucket -> result.add(bucket.toCandle(target, now)));
        return List.copyOf(result);
    }

    public static Instant bucket(Instant instant, CandleInterval interval) {
        if (interval == CandleInterval.ONE_DAY) {
            return instant.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        }
        long seconds = interval.minutes() * 60L;
        return Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), seconds) * seconds)
                .truncatedTo(ChronoUnit.SECONDS);
    }

    private static final class MutableCandle {
        private final Instant startedAt;
        private final BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume = BigDecimal.ZERO;

        private MutableCandle(Instant startedAt, MarketCandle first) {
            this.startedAt = startedAt;
            this.open = first.open();
            this.high = first.high();
            this.low = first.low();
            this.close = first.close();
        }

        private MutableCandle add(MarketCandle candle) {
            high = high.max(candle.high());
            low = low.min(candle.low());
            close = candle.close();
            volume = volume.add(candle.volume());
            return this;
        }

        private MarketCandle toCandle(CandleInterval interval, Instant now) {
            boolean closed = !now.isBefore(startedAt.plusSeconds(interval.minutes() * 60L));
            return new MarketCandle(startedAt, open, high, low, close, volume, closed);
        }
    }
}
