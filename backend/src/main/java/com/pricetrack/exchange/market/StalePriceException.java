package com.pricetrack.exchange.market;

/** Raised before order creation when the last live price is too old to settle safely. */
public class StalePriceException extends RuntimeException {
    public StalePriceException() {
        super("현재 가격이 오래되어 주문할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
