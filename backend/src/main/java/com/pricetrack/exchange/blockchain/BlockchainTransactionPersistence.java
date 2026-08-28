package com.pricetrack.exchange.blockchain;

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
    public void saveSigned(Long orderId, BlockchainTransactionType type, String sender,
            long nonce, String rawTransaction, String txHash) {
        BlockchainTransaction transaction = new BlockchainTransaction();
        transaction.setOrderId(orderId);
        transaction.setType(type);
        transaction.setStatus(BlockchainTransactionStatus.SIGNED);
        transaction.setSenderAddress(sender);
        transaction.setNonce(nonce);
        transaction.setRawTransaction(rawTransaction);
        transaction.setTxHash(txHash);
        transactionRepository.saveAndFlush(transaction);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmitted(Long orderId, String txHash) {
        BlockchainTransaction transaction = transactionRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("블록체인 트랜잭션 기록을 찾을 수 없습니다."));
        transaction.setStatus(BlockchainTransactionStatus.SUBMITTED);
        transaction.setSubmittedAt(Instant.now());

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("주문을 찾을 수 없습니다."));
        order.setTxHash(txHash);
        order.setStatus(OrderStatus.PENDING_ONCHAIN);
        order.setUpdatedAt(Instant.now());
    }
}
