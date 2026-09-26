package com.pricetrack.exchange.blockchain.oracle;

/** 최신 가격 보고서를 안전하게 발급할 수 없을 때 발생한다. */
public class PriceReportIssuanceException extends RuntimeException {
    public PriceReportIssuanceException(String message) {
        super(message);
    }

    public PriceReportIssuanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
