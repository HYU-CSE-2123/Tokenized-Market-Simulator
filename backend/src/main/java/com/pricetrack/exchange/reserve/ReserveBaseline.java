package com.pricetrack.exchange.reserve;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;

/** Singleton append-only anchor. PostgreSQL additionally rejects UPDATE/DELETE/TRUNCATE. */
@Entity @Table(name="reserve_baselines") @Immutable @NoArgsConstructor
@Check(constraints="id=1")
class ReserveBaseline {
    @Id private Long id;
    @Column(nullable=false,columnDefinition="text") private String payload;
    @Column(name="actor_id",nullable=false) private Long actorId;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
