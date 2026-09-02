package com.pricetrack.exchange.blockchain.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Java 가격과 PriceOracle이 사용하는 8 decimals 정수 단위를 변환한다. */
public final class PriceUnits {
    public static final int DECIMALS = 8;

    private PriceUnits() {}

    /** 양수 가격을 Oracle의 priceE8 정수로 변환하며 8자리 초과 정밀도는 거부한다. */
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

    /** 양수 priceE8 정수를 API와 DB가 사용하는 소수 가격으로 변환한다. */
    public static BigDecimal fromPriceE8(BigInteger priceE8) {
        if (priceE8 == null || priceE8.signum() <= 0) throw new IllegalArgumentException("priceE8은 0보다 커야 합니다.");
        return new BigDecimal(priceE8, DECIMALS);
    }
}
