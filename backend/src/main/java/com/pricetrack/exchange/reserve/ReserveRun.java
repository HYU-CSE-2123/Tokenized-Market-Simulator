package com.pricetrack.exchange.reserve;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity @Table(name="reserve_runs") @Immutable @NoArgsConstructor
class ReserveRun {
    @Id @Column(length=36) private String id;
    @Column(nullable=false,columnDefinition="text") private String payload;
    @Column(name="actor_id",nullable=false) private Long actorId;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
