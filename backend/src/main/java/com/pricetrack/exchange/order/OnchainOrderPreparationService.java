package com.pricetrack.exchange.order;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.common.exception.InsufficientBalanceException;
import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.oracle.SignedPriceReport;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionRepository;
import com.pricetrack.exchange.quote.PriceQuote;
import com.pricetrack.exchange.quote.PriceQuoteService;
import com.pricetrack.exchange.quote.PriceQuoteUnavailableException;
import com.pricetrack.exchange.wallet.UserBalance;
import com.pricetrack.exchange.wallet.WalletService;
import com.pricetrack.exchange.websocket.publisher.UserWebSocketPublisher;

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
    private final UserWebSocketPublisher userEvents;
    private final PriceQuoteService priceQuoteService;
    private final BlockchainTransactionRepository transactionRepository;

    public OnchainOrderPreparationService(OrderRepository orderRepository, WalletService walletService,
            UserWebSocketPublisher userEvents, PriceQuoteService priceQuoteService,
            BlockchainTransactionRepository transactionRepository) {
        this.orderRepository = orderRepository;
        this.walletService = walletService;
        this.userEvents = userEvents;
        this.priceQuoteService = priceQuoteService;
        this.transactionRepository = transactionRepository;
    }

    /**
     * 주문을 생성하고 입력 자산을 잠근다.
     * 잔고 부족 주문도 FAILED 기록으로 남겨야 하므로 해당 예외만 rollback 대상에서 제외한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = {
            InsufficientBalanceException.class, PriceQuoteUnavailableException.class
    })
    public PreparedOrder prepare(Long userId, OrderSide side, BigDecimal input, String quoteId) {
        PriceReport.Side reportSide = side == OrderSide.BUY ? PriceReport.Side.BUY : PriceReport.Side.SELL;
        // 견적 검증을 어떤 주문·잔고 변경보다 먼저 수행해야 만료 상태만 안전하게 커밋할 수 있다.
        PriceQuote quote = priceQuoteService.lockAndValidate(userId, quoteId, reportSide, input);
        String inputSymbol = side == OrderSide.BUY ? WalletService.KRW_SYMBOL : WalletService.TOKEN_SYMBOL;
        UserBalance balance = walletService.getForUpdate(userId, inputSymbol);

        Order order = new Order();
        order.setUserId(userId);
        order.setSymbol(WalletService.TOKEN_SYMBOL);
        order.setSide(side);
        order.setInputAmount(input);
        order.setExpectedOutputAmount(TokenUnits.fromWei(quote.getMinimumOutput()));
        order = orderRepository.save(order);

        if (balance.getAvailableAmount().compareTo(input) < 0) {
            // 실패 주문 이력은 보존하되 실제 잔고는 잠그지 않는다.
            order.setStatus(OrderStatus.FAILED);
            order.setUpdatedAt(Instant.now());
            userEvents.publishOrder(order);
            throw new InsufficientBalanceException(inputSymbol);
        }
        balance.lock(input);
        priceQuoteService.markConsumed(quote, order.getId());
        return new PreparedOrder(order, priceQuoteService.signedReport(quote));
    }

    /** SIGNED 복구 원문이 만들어지기 전에 실패한 주문만 실패 처리하고 자산 잠금을 해제한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failIfNotSigned(Long orderId, String reason) {
        if (transactionRepository.findByOrderId(orderId).isPresent()) return;
        Order order = orderRepository.findForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("복구할 주문을 찾을 수 없습니다."));
        if (order.getStatus() != OrderStatus.REQUESTED) return;
        String symbol = order.getSide() == OrderSide.BUY ? WalletService.KRW_SYMBOL : WalletService.TOKEN_SYMBOL;
        UserBalance balance = walletService.getForUpdate(order.getUserId(), symbol);
        if (balance.getLockedAmount().compareTo(order.getInputAmount()) < 0) {
            throw new IllegalStateException("실패 주문의 잠긴 잔고가 입력 수량보다 적습니다.");
        }
        balance.unlock(order.getInputAmount());
        order.setStatus(OrderStatus.FAILED);
        order.setUpdatedAt(Instant.now());
        userEvents.publishOrder(order);
    }

    public record PreparedOrder(Order order, SignedPriceReport signedReport) {}
}
