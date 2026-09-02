package com.pricetrack.exchange.blockchain.transaction;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** 미완료 온체인 작업의 복구·정산에 필요한 조회와 행 잠금을 제공한다. */
public interface BlockchainTransactionRepository extends JpaRepository<BlockchainTransaction, Long> {
    Optional<BlockchainTransaction> findByOrderId(Long orderId);
    Optional<BlockchainTransaction> findByTxHash(String txHash);
    boolean existsByTypeAndStatusIn(BlockchainTransactionType type,
            List<BlockchainTransactionStatus> statuses);
    List<BlockchainTransaction> findAllByStatusInOrderByCreatedAtAsc(
            List<BlockchainTransactionStatus> statuses);

    /** 같은 트랜잭션을 두 scheduler가 동시에 정산하지 않도록 행을 비관적으로 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BlockchainTransaction t where t.id = :id")
    Optional<BlockchainTransaction> findForUpdate(@Param("id") Long id);
}
