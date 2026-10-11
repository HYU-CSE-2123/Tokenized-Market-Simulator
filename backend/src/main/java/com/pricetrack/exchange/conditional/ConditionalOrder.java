package com.pricetrack.exchange.conditional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;
import com.pricetrack.exchange.order.OrderSide;

/** Long-lived intent only. An execution Order and its asset lock are created atomically later. */
@Entity @Table(name="conditional_orders",uniqueConstraints={
    @UniqueConstraint(columnNames={"user_id","request_key_hash"}),
    @UniqueConstraint(columnNames="execution_order_id")})
@Getter @Setter @NoArgsConstructor
public class ConditionalOrder {
    public enum State { WAITING, PREPARING, EXECUTING, SUCCEEDED, FAILED, CANCELLED, EXPIRED, REVIEW_REQUIRED }
    @Id @Column(length=36) private String id;
    @Column(name="user_id",nullable=false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=10) private OrderSide side;
    @Column(name="input_amount",nullable=false,precision=30,scale=18) private BigDecimal inputAmount;
    @Column(name="target_price_e8",nullable=false,precision=78,scale=0) private BigInteger targetPriceE8;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private State state;
    @Column(name="expires_at",nullable=false) private Instant expiresAt;
    @Column(name="next_evaluation_at",nullable=false) private Instant nextEvaluationAt;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Column(name="execution_order_id") private Long executionOrderId;
    @Column(name="worker_owner",length=36) private String workerOwner;
    @Column(name="reason_code",length=80) private String reasonCode;
    @Column(name="request_key_hash",nullable=false,length=64) private String requestKeyHash;
    @Column(name="request_hash",nullable=false,length=64) private String requestHash;
    @Version private long version;
}
