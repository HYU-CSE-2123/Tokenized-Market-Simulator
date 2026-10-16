package com.pricetrack.exchange.wallet;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Independent DB credit evidence, committed or rolled back together with the balance. */
@Entity @Table(name="faucet_grants") @Immutable @Getter @NoArgsConstructor
public class FaucetGrant {
    @Id @Column(length=36) private String id;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(nullable=false,precision=30,scale=18) private BigDecimal amount;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    public FaucetGrant(Long userId, BigDecimal amount) {
        this.id=UUID.randomUUID().toString(); this.userId=userId; this.amount=amount; this.createdAt=Instant.now();
    }
}
