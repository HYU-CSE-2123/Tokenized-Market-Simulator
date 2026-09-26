package com.pricetrack.exchange.order;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionSender;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;

/**
 * 사용자 주문 도메인과 운영자 지갑 트랜잭션 전송을 연결한다.
 * 온체인 준비 상태와 견적을 먼저 확인하고, DB 주문·잔고 잠금을 확정한 다음
 * Vault 호출을 전송한다. 최종 체결은 reconciliation이 비동기로 수행한다.
 */
@Service
public class OnchainOrderService {
    private final BlockchainService blockchainService;
    private final BlockchainTransactionSender transactionSender;
    private final OnchainOrderPreparationService preparationService;
    private final OrderRepository orderRepository;

    public OnchainOrderService(BlockchainService blockchainService,
            BlockchainTransactionSender transactionSender,
            OnchainOrderPreparationService preparationService, OrderRepository orderRepository) {
        this.blockchainService = blockchainService;
        this.transactionSender = transactionSender;
        this.preparationService = preparationService;
        this.orderRepository = orderRepository;
    }

    /** 사용자 견적을 소비하고 mKRW를 잠근 뒤 서명 보고서 기반 Vault.buy를 제출한다. */
    public Order buy(Long userId, BigDecimal krwAmount, String quoteId) {
        return submit(userId, OrderSide.BUY, krwAmount, quoteId);
    }

    /** 사용자 견적을 소비하고 mSEC를 잠근 뒤 서명 보고서 기반 Vault.sell을 제출한다. */
    public Order sell(Long userId, BigDecimal tokenAmount, String quoteId) {
        return submit(userId, OrderSide.SELL, tokenAmount, quoteId);
    }

    private Order submit(Long userId, OrderSide side, BigDecimal input, String quoteId) {
        var prepared = preparationService.prepare(userId, side, input, quoteId);
        Order order = prepared.order();
        var signed = prepared.signedReport();
        try {
            BlockchainTransactionType type = side == OrderSide.BUY
                    ? BlockchainTransactionType.BUY : BlockchainTransactionType.SELL;
            String calldata = side == OrderSide.BUY
                    ? blockchainService.encodeBuy(signed) : blockchainService.encodeSell(signed);
            transactionSender.submit(order.getId(), type,
                    blockchainService.exchangeVaultAddress(), calldata);
        } catch (RuntimeException exception) {
            // SIGNED 저장 전 실패만 보상하고, 저장 후 장애는 동일 raw transaction 복구에 맡긴다.
            try {
                preparationService.failIfNotSigned(order.getId(), exception.getMessage());
            } catch (RuntimeException compensationFailure) {
                exception.addSuppressed(compensationFailure);
            }
            throw exception;
        }
        return reload(order.getId());
    }

    private Order reload(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("생성한 주문을 찾을 수 없습니다."));
    }
}
