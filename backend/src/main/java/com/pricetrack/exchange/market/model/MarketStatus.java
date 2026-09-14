package com.pricetrack.exchange.market.model;

/** 기초 시장의 거래 가능 상태다. 실제 공급자 연동 전에는 UNKNOWN을 사용한다. */
public enum MarketStatus {
    UNKNOWN,
    OPEN,
    CLOSED
}
