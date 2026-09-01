package com.pricetrack.exchange.blockchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.pricetrack.exchange.blockchain.ContractEventParser.SettlementEvent;
import com.pricetrack.exchange.order.Order;
import com.pricetrack.exchange.order.OrderRepository;
import com.pricetrack.exchange.order.OrderSide;
import com.pricetrack.exchange.order.OrderStatus;
import com.pricetrack.exchange.trade.TradeRepository;
import com.pricetrack.exchange.wallet.UserBalance;
import com.pricetrack.exchange.wallet.UserBalanceRepository;
import com.pricetrack.exchange.wallet.WalletService;

@SpringBootTest
class OnchainSettlementServiceTest {
    @Autowired OnchainSettlementService settlementService;
    @Autowired OrderRepository orderRepository;
    @Autowired BlockchainTransactionRepository transactionRepository;
    @Autowired UserBalanceRepository balanceRepository;
    @Autowired TradeRepository tradeRepository;

    @Test
    void buySettlementIsIdempotent() {
        Fixture fixture = fixture(700_001L, OrderSide.BUY, "100000", "1000000", "0");
        SettlementEvent event = new SettlementEvent(BlockchainTransactionType.BUY, operator(),
                TokenUnits.toWei(new BigDecimal("100000")), TokenUnits.toWei(new BigDecimal("1.332")),
                TokenUnits.toWei(new BigDecimal("100")), new BigInteger("7500000000000"));

        settlementService.settleSuccess(fixture.transactionId(), event, 10L);
        settlementService.settleSuccess(fixture.transactionId(), event, 10L);

        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        UserBalance krw = balanceRepository.findByUserIdAndSymbol(fixture.userId(), "mKRW").orElseThrow();
        UserBalance token = balanceRepository.findByUserIdAndSymbol(fixture.userId(), "mSEC").orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(krw.getAmount()).isEqualByComparingTo("900000");
        assertThat(krw.getLockedAmount()).isZero();
        assertThat(token.getAmount()).isEqualByComparingTo("1.332");
        assertThat(token.getAverageBuyPrice()).isEqualByComparingTo("75075.07507508");
        assertThat(tradeRepository.findAll().stream().filter(t -> t.getOrderId().equals(order.getId())).count())
                .isEqualTo(1);
    }

    @Test
    void failedSellUnlocksWithoutChangingAmount() {
        Fixture fixture = fixture(700_002L, OrderSide.SELL, "1", "0", "2");

        settlementService.settleFailure(fixture.transactionId(), 11L, "reverted");
        settlementService.settleFailure(fixture.transactionId(), 11L, "reverted");

        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        UserBalance token = balanceRepository.findByUserIdAndSymbol(fixture.userId(), "mSEC").orElseThrow();
        BlockchainTransaction transaction = transactionRepository.findById(fixture.transactionId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(token.getAmount()).isEqualByComparingTo("2");
        assertThat(token.getLockedAmount()).isZero();
        assertThat(transaction.getStatus()).isEqualTo(BlockchainTransactionStatus.FAILED);
        assertThat(tradeRepository.existsByOrderId(order.getId())).isFalse();
    }

    private Fixture fixture(long userId, OrderSide side, String input, String krwAmount, String tokenAmount) {
        UserBalance krw = new UserBalance(userId, WalletService.KRW_SYMBOL);
        krw.setAmount(new BigDecimal(krwAmount));
        UserBalance token = new UserBalance(userId, WalletService.TOKEN_SYMBOL);
        token.setAmount(new BigDecimal(tokenAmount));
        UserBalance locked = side == OrderSide.BUY ? krw : token;
        locked.setLockedAmount(new BigDecimal(input));
        balanceRepository.save(krw);
        balanceRepository.save(token);

        Order order = new Order();
        order.setUserId(userId);
        order.setSymbol("mSEC");
        order.setSide(side);
        order.setInputAmount(new BigDecimal(input));
        order.setExpectedOutputAmount(BigDecimal.ONE);
        order.setStatus(OrderStatus.PENDING_ONCHAIN);
        order.setTxHash(txHash(userId));
        orderRepository.save(order);

        BlockchainTransaction transaction = new BlockchainTransaction();
        transaction.setOrderId(order.getId());
        transaction.setType(side == OrderSide.BUY ? BlockchainTransactionType.BUY : BlockchainTransactionType.SELL);
        transaction.setStatus(BlockchainTransactionStatus.SUBMITTED);
        transaction.setSenderAddress(operator());
        transaction.setNonce(userId);
        transaction.setTxHash(txHash(userId));
        transactionRepository.save(transaction);
        return new Fixture(userId, order.getId(), transaction.getId());
    }

    private String txHash(long value) { return "0x" + String.format("%064x", value); }
    private String operator() { return "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"; }
    private record Fixture(long userId, Long orderId, Long transactionId) {}
}
