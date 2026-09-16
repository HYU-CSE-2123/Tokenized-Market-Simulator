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

    public SimulatedCandleProvider(MarketCandleRepository repository) { this.repository = repository; }

    @Transactional
    public void record(BigDecimal price, Instant observedAt) {
        update(CandleInterval.ONE_MINUTE, bucket(observedAt, CandleInterval.ONE_MINUTE), price);
        update(CandleInterval.ONE_DAY, bucket(observedAt, CandleInterval.ONE_DAY), price);
    }

    private void update(CandleInterval interval, Instant startedAt, BigDecimal price) {
        MarketCandleEntity candle = repository.findBySymbolAndIntervalAndStartedAt(
                MarketPriceService.SYMBOL, interval, startedAt).orElseGet(() -> create(interval, startedAt, price));
        candle.setHigh(candle.getHigh().max(price));
        candle.setLow(candle.getLow().min(price));
        candle.setClose(price);
        candle.setVolume(candle.getVolume().add(BigDecimal.ONE));
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
    @Transactional(readOnly = true)
    public MarketCandlePage candles(CandleInterval interval, int count, Instant before) {
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
        return new MarketCandlePage(MarketPriceService.SYMBOL, interval, "SIMULATED", candles, nextBefore);
    }

    static Instant bucket(Instant instant, CandleInterval interval) {
        if (interval == CandleInterval.ONE_MINUTE) return instant.truncatedTo(ChronoUnit.MINUTES);
        return instant.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
    }
}
