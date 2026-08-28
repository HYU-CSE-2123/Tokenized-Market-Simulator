package com.pricetrack.exchange.order;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.common.exception.InsufficientBalanceException;
import com.pricetrack.exchange.wallet.UserBalance;
import com.pricetrack.exchange.wallet.WalletService;

@Service
public class OnchainOrderPreparationService {
    private final OrderRepository orderRepository;
    private final WalletService walletService;

    public OnchainOrderPreparationService(OrderRepository orderRepository, WalletService walletService) {
        this.orderRepository = orderRepository;
        this.walletService = walletService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = InsufficientBalanceException.class)
    public Order prepare(Long userId, OrderSide side, BigDecimal input, BigDecimal expectedOutput) {
        String inputSymbol = side == OrderSide.BUY ? WalletService.KRW_SYMBOL : WalletService.TOKEN_SYMBOL;
        UserBalance balance = walletService.getForUpdate(userId, inputSymbol);

        Order order = new Order();
        order.setUserId(userId);
        order.setSymbol(WalletService.TOKEN_SYMBOL);
        order.setSide(side);
        order.setInputAmount(input);
        order.setExpectedOutputAmount(expectedOutput);
        order = orderRepository.save(order);

        if (balance.getAvailableAmount().compareTo(input) < 0) {
            order.setStatus(OrderStatus.FAILED);
            order.setUpdatedAt(Instant.now());
            throw new InsufficientBalanceException(inputSymbol);
        }
        balance.lock(input);
        return order;
    }
}
