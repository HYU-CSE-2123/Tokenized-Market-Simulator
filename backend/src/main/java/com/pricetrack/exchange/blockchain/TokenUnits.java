package com.pricetrack.exchange.blockchain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class TokenUnits {
    public static final int DECIMALS = 18;

    private TokenUnits() {}

    public static BigInteger toWei(BigDecimal amount) {
        try {
            return amount.setScale(DECIMALS, RoundingMode.UNNECESSARY).unscaledValue();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("토큰 수량은 소수점 18자리까지만 지원합니다.", exception);
        }
    }

    public static BigDecimal fromWei(BigInteger amount) {
        return new BigDecimal(amount, DECIMALS);
    }
}
