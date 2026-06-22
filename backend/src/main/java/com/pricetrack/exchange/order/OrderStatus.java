package com.pricetrack.exchange.order;

/** 주문 상태 (기획서 §11.4, §18.1 온체인 비동기성). */
public enum OrderStatus {
    REQUESTED,
    PENDING_ONCHAIN,
    FILLED,
    FAILED,
    CANCELED
}
