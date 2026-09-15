package com.pricetrack.exchange.market.provider.toss;

/** 토스증권 인증·시세 응답이 API 계약을 충족하지 못할 때 발생한다. */
public class TossApiException extends RuntimeException {
    public TossApiException(String message) {
        super(message);
    }

    public TossApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
