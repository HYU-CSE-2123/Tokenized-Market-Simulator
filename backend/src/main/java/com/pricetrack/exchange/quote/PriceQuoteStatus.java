package com.pricetrack.exchange.quote;

/** 사용자에게 발급한 서명 가격 견적의 서버 측 수명주기다. */
public enum PriceQuoteStatus {
    ISSUED,
    CONSUMED,
    EXPIRED
}
