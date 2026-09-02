package com.pricetrack.exchange.blockchain.support;

/** 운영자 잔고·allowance·권한 또는 예상 실행 결과가 거래 조건을 충족하지 않을 때 사용한다. */
public class OperatorNotReadyException extends RuntimeException {
    public OperatorNotReadyException(String message) { super(message); }
}
