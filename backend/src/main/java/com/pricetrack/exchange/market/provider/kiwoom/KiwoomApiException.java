package com.pricetrack.exchange.market.provider.kiwoom;

/** Vendor payload, credentials and transport errors never enter public messages. */
public class KiwoomApiException extends RuntimeException {
    public KiwoomApiException() { super("키움 시세를 현재 사용할 수 없습니다."); }
}
