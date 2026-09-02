package com.pricetrack.exchange.blockchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import com.pricetrack.exchange.order.OnchainOrderService;
import com.pricetrack.exchange.order.Order;
import com.pricetrack.exchange.order.OrderRepository;
import com.pricetrack.exchange.order.OrderStatus;
import com.pricetrack.exchange.wallet.UserBalance;
import com.pricetrack.exchange.wallet.UserBalanceRepository;
import com.pricetrack.exchange.wallet.WalletService;
import com.pricetrack.exchange.trade.TradeRepository;
import com.pricetrack.exchange.market.PriceTickRepository;

/** 실제 Anvil에 buy를 서명·전송하고 DB의 비동기 주문 상태를 확인한다. */
@SpringBootTest(properties = "app.blockchain.enabled=true")
@EnabledIfEnvironmentVariable(named = "BLOCKCHAIN_INTEGRATION_TESTS", matches = "true")
class BlockchainTransactionAnvilIntegrationTest {
    @Autowired OnchainOrderService orderService;
    @Autowired UserBalanceRepository balanceRepository;
    @Autowired BlockchainTransactionRepository transactionRepository;
    @Autowired BlockchainReconciliationService reconciliationService;
    @Autowired OrderRepository orderRepository;
    @Autowired TradeRepository tradeRepository;
    @Autowired Web3j web3j;
    @Autowired BlockchainTransactionSender transactionSender;
    @Autowired BlockchainService blockchainService;
    @Autowired PriceTickRepository priceTickRepository;

    @Test
    void signsBroadcastsAndSettlesBuyTransaction() throws Exception {
        long userId = 999_001L;
        UserBalance krw = new UserBalance(userId, WalletService.KRW_SYMBOL);
        krw.setAmount(new BigDecimal("1000"));
        balanceRepository.save(krw);
        balanceRepository.save(new UserBalance(userId, WalletService.TOKEN_SYMBOL));

        Order order = orderService.buy(userId, new BigDecimal("1000"));
        BlockchainTransaction transaction = transactionRepository.findByOrderId(order.getId()).orElseThrow();
        TransactionReceipt receipt = waitForReceipt(order.getTxHash());
        reconciliationService.reconcilePendingTransactions();

        Order settled = orderRepository.findById(order.getId()).orElseThrow();
        BlockchainTransaction confirmed = transactionRepository.findById(transaction.getId()).orElseThrow();

        assertThat(settled.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(balanceRepository.findByUserIdAndSymbol(userId, WalletService.KRW_SYMBOL)
                .orElseThrow().getLockedAmount()).isZero();
        assertThat(balanceRepository.findByUserIdAndSymbol(userId, WalletService.KRW_SYMBOL)
                .orElseThrow().getAmount()).isEqualByComparingTo("0");
        assertThat(balanceRepository.findByUserIdAndSymbol(userId, WalletService.TOKEN_SYMBOL)
                .orElseThrow().getAmount()).isPositive();
        assertThat(confirmed.getStatus()).isEqualTo(BlockchainTransactionStatus.CONFIRMED);
        assertThat(confirmed.getRawTransaction()).startsWith("0x");
        assertThat(tradeRepository.existsByOrderId(order.getId())).isTrue();
        assertThat(receipt.isStatusOK()).isTrue();
    }

    @Test
    void updatesOracleAndStoresConfirmedPriceTick() throws Exception {
        BigInteger target = blockchainService.oraclePrice().priceE8().add(BigInteger.valueOf(12_300_000));
        var submission = transactionSender.submitSystem(BlockchainTransactionType.UPDATE_PRICE,
                blockchainService.oracleAddress(), blockchainService.encodeUpdatePrice(target), target);
        waitForReceipt(submission.txHash());
        reconciliationService.reconcilePendingTransactions();

        BlockchainTransaction transaction = transactionRepository.findByTxHash(submission.txHash()).orElseThrow();
        assertThat(transaction.getStatus()).isEqualTo(BlockchainTransactionStatus.CONFIRMED);
        assertThat(blockchainService.oraclePrice().priceE8()).isEqualTo(target);
        assertThat(priceTickRepository.existsByBlockchainTransactionId(transaction.getId())).isTrue();
    }

    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            var receipt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receipt.isPresent()) return receipt.get();
            Thread.sleep(100);
        }
        throw new AssertionError("Anvil transaction receipt timeout: " + txHash);
    }
}
