package com.pricetrack.exchange.blockchain.transaction;

import java.time.Instant;
import java.math.BigInteger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DB의 주문 또는 시스템 작업과 하나의 온체인 트랜잭션을 연결하는 복구 원장이다.
 * 주문 거래는 {@code orderId}를 가지며 Oracle 가격 갱신 같은 시스템 거래는 null이다.
 * raw transaction을 보존해 {@code SIGNED} 상태에서 안전하게 재전송할 수 있다.
 */
@Entity
@Table(name = "blockchain_transactions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_blockchain_transactions_order_id", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_blockchain_transactions_sender_nonce",
                columnNames = {"sender_address", "nonce"})
})
@Getter
@Setter
@NoArgsConstructor
public class BlockchainTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** 사용자 주문 거래의 연결 키. UPDATE_PRICE 같은 시스템 거래에는 값이 없다. */
    @Column(name = "order_id", unique = true)
    private Long orderId;
    /** 서명 원문에서 계산되며 체인과 DB에서 동일 거래를 찾는 식별자다. */
    @Column(name = "tx_hash", unique = true)
    private String txHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50)
    private BlockchainTransactionType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private BlockchainTransactionStatus status = BlockchainTransactionStatus.CREATED;
    @Column(name = "sender_address")
    private String senderAddress;
    /** 같은 운영자 주소에서는 한 번만 사용할 수 있는 트랜잭션 순번이다. */
    @Column
    private Long nonce;
    /** 서버 재시작 후 동일 트랜잭션을 재전송하기 위한 서명 완료 원문이다. */
    @Column(name = "raw_transaction", columnDefinition = "TEXT")
    private String rawTransaction;
    /** UPDATE_PRICE 이벤트가 실제 의도한 가격인지 검증하기 위한 priceE8 기대값이다. */
    @Column(name = "target_value", precision = 78, scale = 0)
    private BigInteger targetValue;
    @Column(name = "block_number")
    private Long blockNumber;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
}
