package com.pricetrack.exchange.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 모의 가격 시뮬레이터 (기획서 §8.1).
 * 초기 75,000원, 1초 주기로 -0.3% ~ +0.3% 변동, WebSocket broadcast.
 * TODO(Phase 3): PriceOracle.updatePrice 온체인 반영, price_ticks 저장.
 */
@Component
public class PriceSimulator {

    public static final String SYMBOL = "mSEC";
    private static final BigDecimal INITIAL_PRICE = new BigDecimal("75000");

    private final SimpMessagingTemplate messagingTemplate;
    private final AtomicReference<BigDecimal> currentPrice = new AtomicReference<>(INITIAL_PRICE);

    public PriceSimulator(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice.get();
    }

    @Scheduled(fixedRateString = "${app.price.update-interval-ms:1000}")
    public void tick() {
        BigDecimal previous = currentPrice.get();
        double deltaPct = ThreadLocalRandom.current().nextDouble(-0.003, 0.003);
        BigDecimal next = previous
                .multiply(BigDecimal.valueOf(1 + deltaPct))
                .setScale(8, RoundingMode.HALF_UP);
        currentPrice.set(next);

        BigDecimal changeRate = next.subtract(previous)
                .divide(previous, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        messagingTemplate.convertAndSend(
                "/topic/markets/" + SYMBOL + "/price",
                new PriceEvent("PRICE_UPDATED", SYMBOL, next, changeRate, Instant.now().toString()));
    }

    public record PriceEvent(String type, String symbol, BigDecimal price,
                             BigDecimal changeRate, String timestamp) {}
}
