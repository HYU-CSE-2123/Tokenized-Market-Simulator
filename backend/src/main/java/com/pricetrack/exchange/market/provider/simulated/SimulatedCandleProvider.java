package com.pricetrack.exchange.market.provider.simulated;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.beans.factory.annotation.Autowired;

import com.pricetrack.exchange.market.MarketCandleAggregator;
import com.pricetrack.exchange.market.MarketCandleEntity;
import com.pricetrack.exchange.market.MarketCandleRepository;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.CandleInterval;
import com.pricetrack.exchange.market.model.MarketCandle;
import com.pricetrack.exchange.market.model.MarketCandlePage;
import com.pricetrack.exchange.market.provider.MarketCandleProvider;

@Component
@ConditionalOnProperty(name = "app.price.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedCandleProvider implements MarketCandleProvider {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final MarketCandleRepository repository;
    private final SyntheticMarketStateRepository states;

    @Autowired
    public SimulatedCandleProvider(MarketCandleRepository repository, SyntheticMarketStateRepository states) {
        this.repository = repository; this.states = states;
    }
    SimulatedCandleProvider(MarketCandleRepository repository) { this(repository, null); }

    @Transactional
    public void record(BigDecimal price, Instant observedAt) {
        record(price, BigDecimal.ONE, observedAt);
    }
    @Transactional
    public void record(BigDecimal price, BigDecimal volume, Instant observedAt) {
        update(CandleInterval.ONE_MINUTE, bucket(observedAt, CandleInterval.ONE_MINUTE), price, volume);
        update(CandleInterval.ONE_DAY, bucket(observedAt, CandleInterval.ONE_DAY), price, volume);
    }

    private void update(CandleInterval interval, Instant startedAt, BigDecimal price, BigDecimal volume) {
        MarketCandleEntity candle = repository.findBySymbolAndIntervalAndStartedAt(
                MarketPriceService.SYMBOL, interval, startedAt).orElseGet(() -> create(interval, startedAt, price));
        candle.setHigh(candle.getHigh().max(price));
        candle.setLow(candle.getLow().min(price));
        candle.setClose(price);
        candle.setVolume(candle.getVolume().add(volume));
        repository.save(candle);
    }

    private MarketCandleEntity create(CandleInterval interval, Instant startedAt, BigDecimal price) {
        MarketCandleEntity candle = new MarketCandleEntity();
        candle.setSymbol(MarketPriceService.SYMBOL);
        candle.setInterval(interval);
        candle.setStartedAt(startedAt);
        candle.setOpen(price); candle.setHigh(price); candle.setLow(price); candle.setClose(price);
        candle.setVolume(BigDecimal.ZERO);
        return candle;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MarketCandlePage candles(CandleInterval interval, int count, Instant before) {
        // Checkpoint and OHLCV are read from one DB snapshot, not response wall-clock time.
        Instant asOf = states == null ? null : states.findById(MarketPriceService.SYMBOL)
                .map(SyntheticMarketState::getObservedAt).orElse(null);
        if (interval.isAggregatedIntraday()) {
            MarketCandlePage page = aggregatedCandles(interval, count, before);
            return new MarketCandlePage(page.symbol(), interval, "SIMULATED", page.candles(), page.nextBefore(), asOf);
        }
        List<MarketCandleEntity> entities = repository
                .findBySymbolAndIntervalAndStartedAtLessThanEqualOrderByStartedAtDesc(
                        MarketPriceService.SYMBOL, interval, before, PageRequest.of(0, count + 1));
        Instant nextBefore = entities.size() > count ? entities.get(count).getStartedAt() : null;
        List<MarketCandle> candles = new ArrayList<>();
        Instant currentBucket = bucket(Instant.now(), interval);
        entities.stream().limit(count).forEach(entity -> candles.add(new MarketCandle(
                entity.getStartedAt(), entity.getOpen(), entity.getHigh(), entity.getLow(),
                entity.getClose(), entity.getVolume(), entity.getStartedAt().isBefore(currentBucket))));
        Collections.reverse(candles);
        return new MarketCandlePage(MarketPriceService.SYMBOL, interval, "SIMULATED", candles, nextBefore, asOf);
    }

    private MarketCandlePage aggregatedCandles(CandleInterval interval, int count, Instant before) {
        int sourceCount = (count + 1) * interval.minutes();
        List<MarketCandleEntity> entities = repository
                .findBySymbolAndIntervalAndStartedAtLessThanEqualOrderByStartedAtDesc(
                        MarketPriceService.SYMBOL, CandleInterval.ONE_MINUTE, before,
                        PageRequest.of(0, sourceCount));
        List<MarketCandle> source = new ArrayList<>();
        entities.forEach(entity -> source.add(new MarketCandle(entity.getStartedAt(), entity.getOpen(),
                entity.getHigh(), entity.getLow(), entity.getClose(), entity.getVolume(), true)));
        List<MarketCandle> aggregated = MarketCandleAggregator.aggregate(source, interval, Instant.now());
        int from = Math.max(0, aggregated.size() - count);
        // 시뮬레이션 원본은 시작 시각을 저장하므로 첫 반환 봉 직전 1분까지를 다음 상한으로 삼는다.
        Instant nextBefore = from > 0 ? aggregated.get(from).startedAt().minusSeconds(60) : null;
        return new MarketCandlePage(MarketPriceService.SYMBOL, interval, "SIMULATED",
                aggregated.subList(from, aggregated.size()), nextBefore);
    }

    static Instant bucket(Instant instant, CandleInterval interval) {
        if (interval == CandleInterval.ONE_MINUTE) return instant.truncatedTo(ChronoUnit.MINUTES);
        if (interval == CandleInterval.ONE_DAY) {
            return instant.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        }
        return MarketCandleAggregator.bucket(instant, interval);
    }
}
