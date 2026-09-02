package com.pricetrack.exchange.order;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.springframework.stereotype.Service;

import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
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

    /** mKRW를 잠그고 Vault.buy를 제출한 뒤 PENDING_ONCHAIN 주문을 반환한다. */
    public Order buy(Long userId, BigDecimal krwAmount) {
        BigInteger amountWei = TokenUnits.toWei(krwAmount);
        BlockchainService.BuyReadiness readiness = blockchainService.buyReadiness(amountWei);
        BigDecimal expected = TokenUnits.fromWei(readiness.quote().outputAmount());
        Order order = preparationService.prepare(userId, OrderSide.BUY, krwAmount, expected);
        transactionSender.submit(order.getId(), BlockchainTransactionType.BUY,
                readiness.vaultAddress(), blockchainService.encodeBuy(amountWei));
        return reload(order.getId());
    }

    /** mSEC를 잠그고 Vault.sell을 제출한 뒤 PENDING_ONCHAIN 주문을 반환한다. */
    public Order sell(Long userId, BigDecimal tokenAmount) {
        BigInteger amountWei = TokenUnits.toWei(tokenAmount);
        BlockchainService.SellReadiness readiness = blockchainService.sellReadiness(amountWei);
        BigDecimal expected = TokenUnits.fromWei(readiness.quote().outputAmount());
        Order order = preparationService.prepare(userId, OrderSide.SELL, tokenAmount, expected);
        transactionSender.submit(order.getId(), BlockchainTransactionType.SELL,
                readiness.vaultAddress(), blockchainService.encodeSell(amountWei));
        return reload(order.getId());
    }

    private Order reload(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("생성한 주문을 찾을 수 없습니다."));
    }
}
