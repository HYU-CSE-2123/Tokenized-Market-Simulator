package com.pricetrack.exchange.blockchain.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class PriceUnits {
    public static final int DECIMALS = 8;

    private PriceUnits() {}

    public static BigInteger toPriceE8(BigDecimal price) {
        if (price == null || price.signum() <= 0) throw new IllegalArgumentException("가격은 0보다 커야 합니다.");
        try {
            BigInteger value = price.setScale(DECIMALS, RoundingMode.UNNECESSARY).unscaledValue();
            if (value.signum() <= 0) throw new IllegalArgumentException("priceE8은 0보다 커야 합니다.");
            return value;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("가격은 소수점 8자리까지만 지원합니다.", exception);
        }
    }

    public static BigDecimal fromPriceE8(BigInteger priceE8) {
        if (priceE8 == null || priceE8.signum() <= 0) throw new IllegalArgumentException("priceE8은 0보다 커야 합니다.");
        return new BigDecimal(priceE8, DECIMALS);
    }
}
