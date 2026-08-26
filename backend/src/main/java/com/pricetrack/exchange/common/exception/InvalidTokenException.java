package com.pricetrack.exchange.common.exception;

public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException() {
        super("유효하지 않거나 만료된 인증 토큰입니다.");
    }
}
