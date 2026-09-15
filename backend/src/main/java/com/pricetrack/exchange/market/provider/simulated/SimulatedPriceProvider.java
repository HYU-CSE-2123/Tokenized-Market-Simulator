package com.pricetrack.exchange.market.provider.simulated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;

/** 기본 개발·테스트 모드에서 기존 랜덤 가격 흐름을 제공한다. */
@Component
@ConditionalOnProperty(name = "app.price.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedPriceProvider implements MarketPriceProvider {
    private static final BigDecimal INITIAL_PRICE = new BigDecimal("75000");

    private final MarketWebSocketPublisher marketEvents;
    private final AtomicReference<MarketPriceSnapshot> current = new AtomicReference<>(snapshot(
            INITIAL_PRICE, INITIAL_PRICE, BigDecimal.ZERO, BigDecimal.ZERO));

    public SimulatedPriceProvider(MarketWebSocketPublisher marketEvents) {
        this.marketEvents = marketEvents;
    }

    @Override
    public MarketPriceSnapshot current() {
        return current.get();
    }

    @Scheduled(
            fixedRateString = "${app.price.update-interval-ms:1000}",
            initialDelayString = "${app.price.initial-delay-ms:1000}")
    public void tick() {
        MarketPriceSnapshot previous = current.get();
        double deltaPct = ThreadLocalRandom.current().nextDouble(-0.003, 0.003);
        BigDecimal nextPrice = previous.price()
                .multiply(BigDecimal.valueOf(1 + deltaPct))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal change = nextPrice.subtract(previous.price());
        BigDecimal changeRate = change
                .divide(previous.price(), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        MarketPriceSnapshot next = snapshot(nextPrice, previous.price(), change, changeRate);
        current.set(next);
        marketEvents.publishPrice(next.price(), next.changeRate());
    }

    private static MarketPriceSnapshot snapshot(
            BigDecimal price, BigDecimal referencePrice, BigDecimal change, BigDecimal changeRate) {
        return new MarketPriceSnapshot(
                MarketPriceService.SYMBOL,
                price,
                referencePrice,
                change,
                changeRate,
                MarketStatus.UNKNOWN,
                PriceStatus.SIMULATED,
                "SIMULATED",
                Instant.now());
    }
}
