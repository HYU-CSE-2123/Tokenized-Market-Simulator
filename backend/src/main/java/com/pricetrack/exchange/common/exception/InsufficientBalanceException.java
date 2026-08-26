package com.pricetrack.exchange.common.exception;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String symbol) {
        super(symbol + " 잔고가 부족합니다.");
    }
}
