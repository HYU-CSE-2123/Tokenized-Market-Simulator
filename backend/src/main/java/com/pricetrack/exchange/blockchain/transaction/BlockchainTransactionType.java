package com.pricetrack.exchange.blockchain.transaction;

/** 운영자 지갑이 수행할 수 있는 온체인 작업의 종류다. */
public enum BlockchainTransactionType {
    /** ERC-20 사용 권한 승인. 현재는 준비 스크립트가 담당하고 향후 확장을 위해 유지한다. */
    APPROVE,
    /** mKRW를 입력해 Vault에서 mSEC를 매수하는 주문 거래다. */
    BUY,
    /** mSEC를 소각하고 Vault에서 mKRW를 받는 주문 거래다. */
    SELL,
    /** 주문과 무관하게 PriceOracle 기준 가격을 변경하는 시스템 거래다. */
    UPDATE_PRICE
}
