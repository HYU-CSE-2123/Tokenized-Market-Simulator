package com.pricetrack.exchange.market.model;

/** 가격의 출처와 신선도에 따른 사용 가능 상태다. */
public enum PriceStatus {
    INITIALIZING,
    LIVE,
    DEGRADED,
    STALE,
    SIMULATED
}
