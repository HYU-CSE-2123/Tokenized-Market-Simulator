package com.pricetrack.exchange.blockchain.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Java의 소수 토큰 수량과 ERC-20의 18 decimals 정수 단위를 변환한다. */
public final class TokenUnits {
    public static final int DECIMALS = 18;

    private TokenUnits() {}

    /** mKRW 또는 mSEC 수량을 컨트랙트가 사용하는 최소 단위 정수로 변환한다. */
    public static BigInteger toWei(BigDecimal amount) {
        try {
            return amount.setScale(DECIMALS, RoundingMode.UNNECESSARY).unscaledValue();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("토큰 수량은 소수점 18자리까지만 지원합니다.", exception);
        }
    }

    /** 컨트랙트 최소 단위 정수를 API와 DB가 사용하는 소수 수량으로 변환한다. */
    public static BigDecimal fromWei(BigInteger amount) {
        return new BigDecimal(amount, DECIMALS);
    }
}
