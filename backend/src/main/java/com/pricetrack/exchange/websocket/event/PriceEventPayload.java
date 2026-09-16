package com.pricetrack.exchange.websocket.event;

import java.math.BigDecimal;
import java.time.Instant;

import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;

/** 차트가 REST 재조회 없이 현재 봉을 갱신할 수 있는 공개 가격 tick이다. */
public record PriceEventPayload(
        String symbol,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changeRate,
        BigDecimal volume,
        MarketStatus marketStatus,
        PriceStatus priceStatus,
        String provider,
        Instant observedAt,
        Instant updatedAt) {
}
