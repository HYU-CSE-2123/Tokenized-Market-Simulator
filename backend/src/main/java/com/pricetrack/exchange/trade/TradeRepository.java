package com.pricetrack.exchange.trade;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, Long> {
    List<Trade> findAllByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByOrderId(Long orderId);
}
