package com.pricetrack.exchange.common.exception;

public class BalanceNotFoundException extends RuntimeException {
    public BalanceNotFoundException() {
        super("사용자 잔고가 초기화되지 않았습니다.");
    }
}
