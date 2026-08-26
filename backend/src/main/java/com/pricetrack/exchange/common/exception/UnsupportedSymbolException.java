package com.pricetrack.exchange.common.exception;

public class UnsupportedSymbolException extends RuntimeException {
    public UnsupportedSymbolException() {
        super("지원하지 않는 자산입니다.");
    }
}
