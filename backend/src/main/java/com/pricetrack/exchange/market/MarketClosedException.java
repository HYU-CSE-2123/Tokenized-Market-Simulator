package com.pricetrack.exchange.market;

/** Raised before order creation when the tracked underlying market is closed. */
public class MarketClosedException extends RuntimeException {
    public MarketClosedException() {
        super("현재 삼성전자 거래 가능 시간이 아니므로 주문할 수 없습니다.");
    }
}
