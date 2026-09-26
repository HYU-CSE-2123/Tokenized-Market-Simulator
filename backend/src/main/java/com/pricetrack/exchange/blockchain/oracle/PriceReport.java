package com.pricetrack.exchange.blockchain.oracle;

import java.math.BigInteger;

/** Solidity와 공유하는 주문별 가격 보고서다. 금액은 모두 컨트랙트 정수 단위다. */
public record PriceReport(String quoteId, String symbolHash, BigInteger priceE8,
        BigInteger observedAt, BigInteger validUntil, Side side, BigInteger inputAmount,
        BigInteger minimumOutput, String executor) {

    public enum Side {
        BUY(0), SELL(1);

        private final int code;

        Side(int code) { this.code = code; }

        public int code() { return code; }
    }
}
