package com.pricetrack.exchange.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.BlockchainTransactionPersistence;
import com.pricetrack.exchange.blockchain.BlockchainTransactionRepository;
import com.pricetrack.exchange.blockchain.BlockchainTransactionSender;
import com.pricetrack.exchange.blockchain.BlockchainTransactionType;
import com.pricetrack.exchange.blockchain.ContractGateway;
import com.pricetrack.exchange.wallet.UserBalanceRepository;

@SpringBootTest(properties = "app.blockchain.enabled=true")
@AutoConfigureMockMvc
class OnchainTradingIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BlockchainTransactionPersistence persistence;
    @Autowired BlockchainTransactionRepository transactionRepository;
    @Autowired UserBalanceRepository balanceRepository;
    @Autowired OrderRepository orderRepository;
    @MockBean BlockchainService blockchainService;
    @MockBean BlockchainTransactionSender transactionSender;

    @Test
    void buyLocksBalancePersistsTransactionAndReturnsAccepted() throws Exception {
        String token = signupAndFaucet();
        ContractGateway.Quote quote = new ContractGateway.Quote(
                new BigInteger("1332000000000000000"), new BigInteger("100000000000000000000"));
        when(blockchainService.buyReadiness(any())).thenReturn(
                new BlockchainService.BuyReadiness(quote, address()));
        when(blockchainService.encodeBuy(any())).thenReturn("0xabcdef");
        when(transactionSender.submit(anyLong(), any(), anyString(), anyString())).thenAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            String txHash = "0x" + "ab".repeat(32);
            persistence.saveSigned(orderId, BlockchainTransactionType.BUY, address(), 7L, "0xsigned", txHash);
            persistence.markSubmitted(orderId, txHash);
            return new BlockchainTransactionSender.Submission(txHash, BigInteger.valueOf(7));
        });

        String response = mockMvc.perform(post("/api/orders/buy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":\"100000\"}"))
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
    }

    private String signupAndFaucet() throws Exception {
        String loginId = "chain" + System.nanoTime();
        String signup = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId
                                + "\",\"password\":\"password123\",\"nickname\":\"Chain\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(signup).get("accessToken").asText();
        mockMvc.perform(post("/api/wallet/faucet").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return token;
    }

    private String address() { return "0x5FbDB2315678afecb367f032d93F642f64180aa3"; }
}
