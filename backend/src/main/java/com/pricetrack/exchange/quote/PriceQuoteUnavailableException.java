package com.pricetrack.exchange.quote;

public class PriceQuoteUnavailableException extends RuntimeException {
    public PriceQuoteUnavailableException(String message) {
        super(message);
    }
}
