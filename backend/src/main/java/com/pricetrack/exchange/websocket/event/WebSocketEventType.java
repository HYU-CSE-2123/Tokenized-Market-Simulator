package com.pricetrack.exchange.websocket.event;

/** 클라이언트가 공통 envelope의 data 형식을 구분하는 이벤트 종류다. */
public enum WebSocketEventType {
    PRICE_UPDATED,
    TRADE_EXECUTED,
    ORDER_PENDING_ONCHAIN,
    ORDER_FILLED,
    ORDER_FAILED,
    PORTFOLIO_UPDATED
}
