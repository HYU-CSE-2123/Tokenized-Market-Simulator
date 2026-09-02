package com.pricetrack.exchange.order;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.common.exception.InsufficientBalanceException;
import com.pricetrack.exchange.wallet.UserBalance;
import com.pricetrack.exchange.wallet.WalletService;

/**
 * 온체인 주문을 보내기 전에 주문과 입력 자산 잠금을 먼저 확정한다.
 *
 * <p>매수는 mKRW, 매도는 mSEC를 잠가 receipt 대기 중 같은 자산이 다른 주문에
 * 재사용되는 것을 막는다. 별도 트랜잭션으로 커밋하므로 이후 RPC 전송이 실패해도
 * 복구할 주문과 잠금 정보가 남는다.</p>
 */
@Service
public class OnchainOrderPreparationService {
    private final OrderRepository orderRepository;
    private final WalletService walletService;

    public OnchainOrderPreparationService(OrderRepository orderRepository, WalletService walletService) {
        this.orderRepository = orderRepository;
        this.walletService = walletService;
    }

    /**
     * 주문을 생성하고 입력 자산을 잠근다.
     * 잔고 부족 주문도 FAILED 기록으로 남겨야 하므로 해당 예외만 rollback 대상에서 제외한다.
     */
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
            // 실패 주문 이력은 보존하되 실제 잔고는 잠그지 않는다.
            order.setStatus(OrderStatus.FAILED);
            order.setUpdatedAt(Instant.now());
            throw new InsufficientBalanceException(inputSymbol);
        }
        balance.lock(input);
        return order;
    }
}
