package com.pricetrack.exchange.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.oracle.PriceReportStartupValidator;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionPersistence;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionRepository;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionSender;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;
import com.pricetrack.exchange.wallet.UserBalanceRepository;
import com.pricetrack.exchange.websocket.publisher.UserWebSocketPublisher;
import com.pricetrack.exchange.quote.PriceQuote;
import com.pricetrack.exchange.quote.PriceQuoteRepository;
import com.pricetrack.exchange.user.UserRepository;
import com.pricetrack.exchange.blockchain.support.BlockchainConfigurationException;

@SpringBootTest(properties = {
        "app.blockchain.enabled=true",
        "app.blockchain.price-report.enabled=true"
})
@AutoConfigureMockMvc
class OnchainTradingIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BlockchainTransactionPersistence persistence;
    @Autowired BlockchainTransactionRepository transactionRepository;
    @Autowired UserBalanceRepository balanceRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PriceQuoteRepository quoteRepository;
    @Autowired UserRepository userRepository;
    @MockBean BlockchainService blockchainService;
    @MockBean PriceReportStartupValidator startupValidator;
    @MockBean BlockchainTransactionSender transactionSender;
    @MockBean UserWebSocketPublisher userEvents;

    @Test
    void buyLocksBalancePersistsTransactionAndReturnsAccepted() throws Exception {
        TestUser user = signupAndFaucet();
        String quoteId = "0x" + "11".repeat(32);
        quoteRepository.save(quote(user.userId(), quoteId));
        when(blockchainService.encodeBuy(any())).thenReturn("0xabcdef");
        when(blockchainService.exchangeVaultAddress()).thenReturn(address());
        when(transactionSender.submit(anyLong(), any(), anyString(), anyString())).thenAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            String txHash = "0x" + "ab".repeat(32);
            persistence.saveSigned(orderId, BlockchainTransactionType.BUY, address(), 7L, "0xsigned", txHash);
            persistence.markSubmitted(orderId, txHash);
            return new BlockchainTransactionSender.Submission(txHash, BigInteger.valueOf(7));
        });

        String response = mockMvc.perform(post("/api/orders/buy")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":\"100000\",\"quoteId\":\""
                                + quoteId + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_ONCHAIN"))
                .andExpect(jsonPath("$.txHash").exists())
                .andReturn().getResponse().getContentAsString();

        long orderId = objectMapper.readTree(response).get("orderId").asLong();
        assertThat(transactionRepository.findByOrderId(orderId)).get()
                .extracting(transaction -> transaction.getStatus().name()).isEqualTo("SUBMITTED");
        Long userId = orderRepository.findById(orderId).orElseThrow().getUserId();
        assertThat(balanceRepository.findByUserIdAndSymbol(userId, "mKRW").orElseThrow().getLockedAmount())
                .isEqualByComparingTo("100000");
        assertThat(quoteRepository.findById(quoteId).orElseThrow().getOrderId()).isEqualTo(orderId);
        verify(userEvents).publishOrder(any(Order.class));
    }

    @Test
    void rpcFailureBeforeSignedRecordFailsOrderUnlocksBalanceAndKeepsQuoteConsumed() throws Exception {
        TestUser user = signupAndFaucet();
        String quoteId = "0x" + "33".repeat(32);
        quoteRepository.save(quote(user.userId(), quoteId));
        when(blockchainService.encodeBuy(any())).thenReturn("0xabcdef");
        when(blockchainService.exchangeVaultAddress()).thenReturn(address());
        doThrow(new BlockchainConfigurationException("RPC unavailable"))
                .when(transactionSender).submit(anyLong(), any(), anyString(), anyString());

        mockMvc.perform(post("/api/orders/buy")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":\"100000\",\"quoteId\":\""
                                + quoteId + "\"}"))
                .andExpect(status().isServiceUnavailable());

        Order failed = orderRepository.findAllByUserIdOrderByCreatedAtDesc(user.userId()).getFirst();
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(transactionRepository.findByOrderId(failed.getId())).isEmpty();
        assertThat(balanceRepository.findByUserIdAndSymbol(user.userId(), "mKRW")
                .orElseThrow().getLockedAmount()).isZero();
        assertThat(quoteRepository.findById(quoteId).orElseThrow().getStatus().name()).isEqualTo("CONSUMED");
    }

    @Test
    void rpcFailureAfterSignedRecordKeepsRecoverableOrderAndLock() throws Exception {
        TestUser user = signupAndFaucet();
        String quoteId = "0x" + "44".repeat(32);
        quoteRepository.save(quote(user.userId(), quoteId));
        when(blockchainService.encodeBuy(any())).thenReturn("0xabcdef");
        when(blockchainService.exchangeVaultAddress()).thenReturn(address());
        when(transactionSender.submit(anyLong(), any(), anyString(), anyString())).thenAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            persistence.saveSigned(orderId, BlockchainTransactionType.BUY, address(), 8L,
                    "0xsigned", "0x" + "cd".repeat(32));
            throw new BlockchainConfigurationException("RPC response lost");
        });

        mockMvc.perform(post("/api/orders/buy")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":\"100000\",\"quoteId\":\""
                                + quoteId + "\"}"))
                .andExpect(status().isServiceUnavailable());

        Order recoverable = orderRepository.findAllByUserIdOrderByCreatedAtDesc(user.userId()).getFirst();
        assertThat(recoverable.getStatus()).isEqualTo(OrderStatus.REQUESTED);
        assertThat(transactionRepository.findByOrderId(recoverable.getId())).get()
                .extracting(transaction -> transaction.getStatus().name()).isEqualTo("SIGNED");
        assertThat(balanceRepository.findByUserIdAndSymbol(user.userId(), "mKRW")
                .orElseThrow().getLockedAmount()).isEqualByComparingTo("100000");
        assertThat(quoteRepository.findById(quoteId).orElseThrow().getStatus().name()).isEqualTo("CONSUMED");
    }

    private TestUser signupAndFaucet() throws Exception {
        String loginId = "chain" + System.nanoTime();
        String signup = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId
                                + "\",\"password\":\"password123\",\"nickname\":\"Chain\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(signup).get("accessToken").asText();
        mockMvc.perform(post("/api/wallet/faucet").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return new TestUser(token, userRepository.findByLoginId(loginId).orElseThrow().getId());
    }

    private String address() { return "0x5FbDB2315678afecb367f032d93F642f64180aa3"; }

    private PriceQuote quote(Long userId, String quoteId) {
        PriceQuote quote = new PriceQuote();
        quote.setQuoteId(quoteId);
        quote.setUserId(userId);
        quote.setSymbol("mSEC");
        quote.setSide(PriceReport.Side.BUY);
        quote.setPriceE8(BigInteger.valueOf(75_000).multiply(BigInteger.TEN.pow(8)));
        quote.setInputAmount(TokenUnits.toWei(new BigDecimal("100000")));
        quote.setMinimumOutput(new BigInteger("1332000000000000000"));
        quote.setFee(TokenUnits.toWei(new BigDecimal("100")));
        quote.setExecutor(address());
        quote.setSignature("0x" + "aa".repeat(65));
        quote.setObservedAt(Instant.now().minusSeconds(1));
        quote.setValidUntil(Instant.now().plusSeconds(20));
        quote.setCreatedAt(Instant.now());
        return quote;
    }

    private record TestUser(String token, Long userId) {}
}
