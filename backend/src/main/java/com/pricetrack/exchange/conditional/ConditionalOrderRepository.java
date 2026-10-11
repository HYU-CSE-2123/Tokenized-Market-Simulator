package com.pricetrack.exchange.conditional;

import java.util.*;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface ConditionalOrderRepository extends JpaRepository<ConditionalOrder,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConditionalOrder c where c.id=:id")
    Optional<ConditionalOrder> lock(String id);
    Optional<ConditionalOrder> findByUserIdAndRequestKeyHash(Long userId,String requestKeyHash);
    List<ConditionalOrder> findAllByUserIdOrderByCreatedAtDesc(Long userId,Pageable page);
    List<ConditionalOrder> findAllByOrderByCreatedAtDesc(Pageable page);
    @Query("select c from ConditionalOrder c where (:uid is null or c.userId=:uid) and (c.createdAt<:at or (c.createdAt=:at and c.id<:id)) order by c.createdAt desc,c.id desc")
    List<ConditionalOrder> history(Long uid,Instant at,String id,Pageable page);
    @Query("select c.id from ConditionalOrder c where c.state in :states and c.nextEvaluationAt<=:at order by c.nextEvaluationAt,c.createdAt,c.id")
    List<String> due(Collection<ConditionalOrder.State> states,Instant at,Pageable page);
}
