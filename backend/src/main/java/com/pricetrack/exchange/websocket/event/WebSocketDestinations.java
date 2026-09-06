package com.pricetrack.exchange.websocket.event;

/** 서버와 클라이언트가 공유하는 STOMP destination의 단일 정의다. */
public final class WebSocketDestinations {
    public static final String PRICE_TOPIC = "/topic/markets/mSEC/price";
    public static final String TRADES_TOPIC = "/topic/markets/mSEC/trades";
    public static final String ORDERS_QUEUE = "/queue/orders";
    public static final String PORTFOLIO_QUEUE = "/queue/portfolio";
    public static final String ORDERS_SUBSCRIPTION = "/user" + ORDERS_QUEUE;
    public static final String PORTFOLIO_SUBSCRIPTION = "/user" + PORTFOLIO_QUEUE;

    private WebSocketDestinations() {}
}
