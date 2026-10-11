package com.pricetrack.exchange.blockchain.observation;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;

/** Latest recorded completed attempt; never the authority for transaction/asset state. */
@Entity @Table(name="blockchain_transaction_observations") @Getter @Setter @NoArgsConstructor
public class TransactionObservation {
    @Id @Column(name="transaction_id") private Long transactionId;
    @Column(name="attempt_id",nullable=false,length=36) private String attemptId;
    @Column(name="started_at",nullable=false) private Instant startedAt;
    @Column(name="finished_at",nullable=false) private Instant finishedAt;
    @Column(nullable=false,length=32) private String phase;
    @Column(name="result_code",nullable=false,length=48) private String resultCode;
    @Column(name="attempt_count",nullable=false) private long attemptCount;
    @Column(name="recorded_at",nullable=false) private Instant recordedAt;
}
