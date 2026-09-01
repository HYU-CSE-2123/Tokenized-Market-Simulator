package com.pricetrack.exchange.blockchain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.blockchain.ContractEventParser.SettlementEvent;
import com.pricetrack.exchange.order.Order;
import com.pricetrack.exchange.order.OrderRepository;
import com.pricetrack.exchange.order.OrderSide;
import com.pricetrack.exchange.order.OrderStatus;
import com.pricetrack.exchange.trade.Trade;
import com.pricetrack.exchange.trade.TradeRepository;
import com.pricetrack.exchange.wallet.UserBalance;
import com.pricetrack.exchange.wallet.WalletService;

@Service
public class OnchainSettlementService {
    private final BlockchainTransactionRepository transactionRepository;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final WalletService walletService;

    public OnchainSettlementService(BlockchainTransactionRepository transactionRepository,
            OrderRepository orderRepository, TradeRepository tradeRepository, WalletService walletService) {
        this.transactionRepository = transactionRepository;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.walletService = walletService;
    }

    @Transactional
    public void settleSuccess(Long transactionId, SettlementEvent event, long blockNumber) {
        BlockchainTransaction transaction = transactionForUpdate(transactionId);
        Order order = orderForUpdate(transaction.getOrderId());
        if (transaction.getStatus() == BlockchainTransactionStatus.CONFIRMED
                || order.getStatus() == OrderStatus.FILLED) return;
        requirePending(transaction, order);
        validateEventAgainstOrder(order, event);

        UserBalance krw = walletService.getForUpdate(order.getUserId(), WalletService.KRW_SYMBOL);
        UserBalance token = walletService.getForUpdate(order.getUserId(), WalletService.TOKEN_SYMBOL);
        BigDecimal input = TokenUnits.fromWei(event.inputAmount());
        BigDecimal output = TokenUnits.fromWei(event.outputAmount());
        BigDecimal fee = TokenUnits.fromWei(event.fee());
        BigDecimal price = new BigDecimal(event.priceE8(), 8);

        if (order.getSide() == OrderSide.BUY) {
            requireLockedAndOwned(krw, input);
            krw.setAmount(krw.getAmount().subtract(input));
            krw.unlock(input);
            updateAverageBuyPrice(token, output, input);
            token.setAmount(token.getAmount().add(output));
        } else {
            requireLockedAndOwned(token, input);
            token.setAmount(token.getAmount().subtract(input));
            token.unlock(input);
            if (token.getAmount().signum() == 0) token.setAverageBuyPrice(BigDecimal.ZERO);
            krw.setAmount(krw.getAmount().add(output));
        }

        if (tradeRepository.existsByOrderId(order.getId())) {
            throw new SettlementConsistencyException("이미 체결이 존재하지만 주문이 FILLED가 아닙니다.");
        }
        Trade trade = new Trade();
        trade.setOrderId(order.getId());
        trade.setUserId(order.getUserId());
        trade.setSymbol(order.getSymbol());
        trade.setSide(order.getSide());
        trade.setPrice(price);
        trade.setBaseAmount(order.getSide() == OrderSide.BUY ? output : input);
        trade.setQuoteAmount(order.getSide() == OrderSide.BUY ? input : output);
        trade.setFee(fee);
        trade.setTxHash(transaction.getTxHash());
        tradeRepository.save(trade);

        Instant now = Instant.now();
        order.setStatus(OrderStatus.FILLED);
        order.setUpdatedAt(now);
        transaction.setStatus(BlockchainTransactionStatus.CONFIRMED);
        transaction.setBlockNumber(blockNumber);
        transaction.setConfirmedAt(now);
        transaction.setErrorMessage(null);
    }

    @Transactional
    public void settleFailure(Long transactionId, long blockNumber, String reason) {
        BlockchainTransaction transaction = transactionForUpdate(transactionId);
        Order order = orderForUpdate(transaction.getOrderId());
        if (transaction.getStatus() == BlockchainTransactionStatus.FAILED
                || order.getStatus() == OrderStatus.FAILED) return;
        requirePending(transaction, order);

        String symbol = order.getSide() == OrderSide.BUY ? WalletService.KRW_SYMBOL : WalletService.TOKEN_SYMBOL;
        UserBalance balance = walletService.getForUpdate(order.getUserId(), symbol);
        if (balance.getLockedAmount().compareTo(order.getInputAmount()) < 0) {
            throw new SettlementConsistencyException("실패 주문의 잠긴 잔고가 입력 수량보다 적습니다.");
        }
        balance.unlock(order.getInputAmount());
        Instant now = Instant.now();
        order.setStatus(OrderStatus.FAILED);
        order.setUpdatedAt(now);
        transaction.setStatus(BlockchainTransactionStatus.FAILED);
        transaction.setBlockNumber(blockNumber);
        transaction.setConfirmedAt(now);
        transaction.setErrorMessage(limit(reason));
    }

    @Transactional
    public void markReviewRequired(Long transactionId, String reason) {
        BlockchainTransaction transaction = transactionForUpdate(transactionId);
        if (transaction.getStatus() == BlockchainTransactionStatus.CONFIRMED
                || transaction.getStatus() == BlockchainTransactionStatus.FAILED) return;
        transaction.setStatus(BlockchainTransactionStatus.REVIEW_REQUIRED);
        transaction.setErrorMessage(limit(reason));
    }

    private void validateEventAgainstOrder(Order order, SettlementEvent event) {
        BlockchainTransactionType expected = order.getSide() == OrderSide.BUY
                ? BlockchainTransactionType.BUY : BlockchainTransactionType.SELL;
        if (event.type() != expected || !TokenUnits.toWei(order.getInputAmount()).equals(event.inputAmount())) {
            throw new SettlementConsistencyException("이벤트 종류 또는 입력 수량이 주문과 다릅니다.");
        }
    }

    private void requirePending(BlockchainTransaction transaction, Order order) {
        if (transaction.getStatus() != BlockchainTransactionStatus.SUBMITTED
                || order.getStatus() != OrderStatus.PENDING_ONCHAIN) {
            throw new SettlementConsistencyException("정산 가능한 pending 상태가 아닙니다.");
        }
    }

    private void requireLockedAndOwned(UserBalance balance, BigDecimal input) {
        if (balance.getLockedAmount().compareTo(input) < 0 || balance.getAmount().compareTo(input) < 0) {
            throw new SettlementConsistencyException(balance.getSymbol() + " 정산 잔고가 일치하지 않습니다.");
        }
    }

    private void updateAverageBuyPrice(UserBalance token, BigDecimal addedTokens, BigDecimal cost) {
        BigDecimal oldCost = token.getAmount().multiply(token.getAverageBuyPrice());
        BigDecimal newAmount = token.getAmount().add(addedTokens);
        token.setAverageBuyPrice(oldCost.add(cost).divide(newAmount, 8, RoundingMode.HALF_UP));
    }

    private BlockchainTransaction transactionForUpdate(Long id) {
        return transactionRepository.findForUpdate(id)
                .orElseThrow(() -> new SettlementConsistencyException("블록체인 트랜잭션을 찾을 수 없습니다."));
    }

    private Order orderForUpdate(Long id) {
        return orderRepository.findForUpdate(id)
                .orElseThrow(() -> new SettlementConsistencyException("주문을 찾을 수 없습니다."));
    }

    private String limit(String message) {
        if (message == null) return "알 수 없는 온체인 실패";
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }

    public static class SettlementConsistencyException extends RuntimeException {
        public SettlementConsistencyException(String message) { super(message); }
    }
}
