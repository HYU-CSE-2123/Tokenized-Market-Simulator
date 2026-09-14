package com.pricetrack.exchange.market.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** 외부 공급자 종류와 무관하게 서비스 내부에서 사용하는 단일 가격 표현이다. */
public record MarketPriceSnapshot(
        String symbol,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changeRate,
        MarketStatus marketStatus,
        PriceStatus priceStatus,
        String provider,
        Instant observedAt) {

    public MarketPriceSnapshot {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(previousClose, "previousClose");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(changeRate, "changeRate");
        Objects.requireNonNull(marketStatus, "marketStatus");
        Objects.requireNonNull(priceStatus, "priceStatus");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(observedAt, "observedAt");
        if (price.signum() <= 0) throw new IllegalArgumentException("price는 0보다 커야 합니다.");
    }
}
