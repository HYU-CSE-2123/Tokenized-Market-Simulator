package com.pricetrack.exchange.blockchain.transaction;

import java.math.BigInteger;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.order.Order;
import com.pricetrack.exchange.order.OrderRepository;
import com.pricetrack.exchange.order.OrderStatus;

/**
 * 온체인 전송 단계의 상태를 독립 DB 트랜잭션으로 확정한다.
 *
 * <p>주문 준비, 서명 정보 저장, RPC 제출 상태를 분리해 커밋함으로써 프로세스가
 * 어느 시점에 중단돼도 reconciliation이 마지막으로 확정된 상태에서 복구할 수 있다.</p>
 */
@Service
public class BlockchainTransactionPersistence {
    private final BlockchainTransactionRepository transactionRepository;
    private final OrderRepository orderRepository;

    public BlockchainTransactionPersistence(BlockchainTransactionRepository transactionRepository,
            OrderRepository orderRepository) {
        this.transactionRepository = transactionRepository;
        this.orderRepository = orderRepository;
    }

    /** 주문에 연결된 서명 트랜잭션을 RPC 전송 전에 독립 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BlockchainTransaction saveSigned(Long orderId, BlockchainTransactionType type, String sender,
            long nonce, String rawTransaction, String txHash) {
        return saveSigned(orderId, type, sender, nonce, rawTransaction, txHash, null);
    }

    /**
     * 주문 또는 시스템 트랜잭션의 복구 정보를 RPC 전송 전에 독립 커밋한다.
     * {@code targetValue}는 현재 Oracle 가격 갱신의 기대값으로 사용한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BlockchainTransaction saveSigned(Long orderId, BlockchainTransactionType type, String sender,
            long nonce, String rawTransaction, String txHash, BigInteger targetValue) {
        BlockchainTransaction transaction = new BlockchainTransaction();
        transaction.setOrderId(orderId);
        transaction.setType(type);
        transaction.setStatus(BlockchainTransactionStatus.SIGNED);
        transaction.setSenderAddress(sender);
        transaction.setNonce(nonce);
        transaction.setRawTransaction(rawTransaction);
        transaction.setTxHash(txHash);
        transaction.setTargetValue(targetValue);
        return transactionRepository.saveAndFlush(transaction);
    }

    /** 이전 호출부와의 호환을 유지하며 txHash 기준 제출 처리로 위임한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmitted(Long orderId, String txHash) {
        markSubmitted(txHash);
    }

    /**
     * RPC 제출이 확인된 트랜잭션을 SUBMITTED로 바꾼다.
     * 주문 거래라면 연결된 주문도 PENDING_ONCHAIN으로 함께 전환한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmitted(String txHash) {
        BlockchainTransaction transaction = transactionRepository.findByTxHash(txHash)
                .orElseThrow(() -> new IllegalStateException("블록체인 트랜잭션 기록을 찾을 수 없습니다."));
        transaction.setStatus(BlockchainTransactionStatus.SUBMITTED);
        transaction.setSubmittedAt(Instant.now());

        if (transaction.getOrderId() != null) {
            Order order = orderRepository.findById(transaction.getOrderId())
                    .orElseThrow(() -> new IllegalStateException("주문을 찾을 수 없습니다."));
            order.setTxHash(txHash);
            order.setStatus(OrderStatus.PENDING_ONCHAIN);
            order.setUpdatedAt(Instant.now());
        }
    }
}
