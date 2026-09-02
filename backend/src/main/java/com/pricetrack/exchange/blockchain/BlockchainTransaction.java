package com.pricetrack.exchange.blockchain;

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
    @Column(name = "order_id", unique = true)
    private Long orderId;
    @Column(name = "tx_hash", unique = true)
    private String txHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50)
    private BlockchainTransactionType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private BlockchainTransactionStatus status = BlockchainTransactionStatus.CREATED;
    @Column(name = "sender_address")
    private String senderAddress;
    @Column
    private Long nonce;
    @Column(name = "raw_transaction", columnDefinition = "TEXT")
    private String rawTransaction;
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
