package com.pricetrack.exchange.blockchain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockchainTransactionRepository extends JpaRepository<BlockchainTransaction, Long> {
    Optional<BlockchainTransaction> findByOrderId(Long orderId);
}
