package com.pricetrack.exchange.blockchain.transaction;

/** 백엔드가 추적하는 하나의 온체인 트랜잭션 생명주기다. */
public enum BlockchainTransactionStatus {
    /** 객체의 초기 상태. 정상 전송 흐름에서는 서명 저장과 함께 곧바로 SIGNED가 된다. */
    CREATED,
    /** nonce와 서명 원문은 DB에 저장됐지만 RPC 제출 완료를 확신할 수 없는 상태다. */
    SIGNED,
    /** RPC가 트랜잭션을 수락했으며 receipt 확정을 기다리는 상태다. */
    SUBMITTED,
    /** receipt와 이벤트 검증 및 DB 정산까지 모두 완료된 상태다. */
    CONFIRMED,
    /** 실패 receipt를 확인하고 주문 자산 잠금 해제까지 완료한 상태다. */
    FAILED,
    /** 체인 결과와 DB 기대값이 달라 자동 자산 변경을 중지한 상태다. */
    REVIEW_REQUIRED
}
