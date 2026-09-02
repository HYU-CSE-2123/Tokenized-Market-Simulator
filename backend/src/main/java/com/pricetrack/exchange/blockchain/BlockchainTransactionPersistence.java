package com.pricetrack.exchange.blockchain;

import java.math.BigInteger;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.order.Order;
import com.pricetrack.exchange.order.OrderRepository;
import com.pricetrack.exchange.order.OrderStatus;

@Service
public class BlockchainTransactionPersistence {
    private final BlockchainTransactionRepository transactionRepository;
    private final OrderRepository orderRepository;

    public BlockchainTransactionPersistence(BlockchainTransactionRepository transactionRepository,
            OrderRepository orderRepository) {
        this.transactionRepository = transactionRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BlockchainTransaction saveSigned(Long orderId, BlockchainTransactionType type, String sender,
            long nonce, String rawTransaction, String txHash) {
        return saveSigned(orderId, type, sender, nonce, rawTransaction, txHash, null);
    }

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmitted(Long orderId, String txHash) {
        markSubmitted(txHash);
    }

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
