package com.pricetrack.exchange.websocket.event;

import java.math.BigDecimal;

import com.pricetrack.exchange.order.OrderSide;
import com.pricetrack.exchange.order.OrderStatus;

/** 주문 상태 알림에 노출하는 사용자 소유 주문 정보다. */
public record OrderEventPayload(Long orderId, String symbol, OrderSide side,
        BigDecimal inputAmount, BigDecimal outputAmount, OrderStatus status, String txHash) {
}
