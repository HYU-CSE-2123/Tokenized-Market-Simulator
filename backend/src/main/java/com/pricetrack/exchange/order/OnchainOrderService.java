package com.pricetrack.exchange.order;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.springframework.stereotype.Service;

import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.BlockchainTransactionSender;
import com.pricetrack.exchange.blockchain.BlockchainTransactionType;
import com.pricetrack.exchange.blockchain.TokenUnits;

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

    public Order buy(Long userId, BigDecimal krwAmount) {
        BigInteger amountWei = TokenUnits.toWei(krwAmount);
        BlockchainService.BuyReadiness readiness = blockchainService.buyReadiness(amountWei);
        BigDecimal expected = TokenUnits.fromWei(readiness.quote().outputAmount());
        Order order = preparationService.prepare(userId, OrderSide.BUY, krwAmount, expected);
        transactionSender.submit(order.getId(), BlockchainTransactionType.BUY,
                readiness.vaultAddress(), blockchainService.encodeBuy(amountWei));
        return reload(order.getId());
    }

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
