package com.pricetrack.exchange.quote;

public class PriceQuoteNotFoundException extends RuntimeException {
    public PriceQuoteNotFoundException() {
        super("가격 견적을 찾을 수 없습니다.");
    }
}
