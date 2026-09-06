package com.pricetrack.exchange.websocket.event;

import java.math.BigDecimal;
import java.time.Instant;

import com.pricetrack.exchange.order.OrderSide;

/** 공개 체결 스트림에 사용하며 사용자 식별 정보는 포함하지 않는다. */
public record TradeEventPayload(Long tradeId, String symbol, OrderSide side, BigDecimal price,
        BigDecimal baseAmount, BigDecimal quoteAmount, BigDecimal fee, Instant createdAt) {
}
