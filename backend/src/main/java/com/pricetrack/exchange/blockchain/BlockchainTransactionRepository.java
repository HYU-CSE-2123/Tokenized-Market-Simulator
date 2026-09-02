package com.pricetrack.exchange.blockchain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface BlockchainTransactionRepository extends JpaRepository<BlockchainTransaction, Long> {
    Optional<BlockchainTransaction> findByOrderId(Long orderId);
    Optional<BlockchainTransaction> findByTxHash(String txHash);
    boolean existsByTypeAndStatusIn(BlockchainTransactionType type,
            List<BlockchainTransactionStatus> statuses);
    List<BlockchainTransaction> findAllByStatusInOrderByCreatedAtAsc(
            List<BlockchainTransactionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BlockchainTransaction t where t.id = :id")
    Optional<BlockchainTransaction> findForUpdate(@Param("id") Long id);
}
