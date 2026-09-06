package com.pricetrack.exchange.websocket.event;

import java.math.BigDecimal;

/** 공개 가격 스트림의 종목·가격·직전 가격 대비 변동률이다. */
public record PriceEventPayload(String symbol, BigDecimal price, BigDecimal changeRate) {
}
