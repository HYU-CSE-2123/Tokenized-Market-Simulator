package com.pricetrack.exchange.order;

import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.trade.TradeRepository;
import com.pricetrack.exchange.user.UserRepository;
import com.pricetrack.exchange.wallet.UserBalanceRepository;

@SpringBootTest
@AutoConfigureMockMvc
class TradingIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TradeRepository tradeRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired UserBalanceRepository balanceRepository;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
        balanceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void fullMockTradingFlow() throws Exception {
        String token = signup("trader1");

        mockMvc.perform(post("/api/wallet/faucet").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedAmount", comparesEqualTo(new BigDecimal("1000000"))))
                .andExpect(jsonPath("$.balance", comparesEqualTo(new BigDecimal("1000000"))));

        String buyResponse = mockMvc.perform(post("/api/orders/buy")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":750000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.side", is("BUY")))
                .andExpect(jsonPath("$.status", is("FILLED")))
                .andExpect(jsonPath("$.outputAmount", comparesEqualTo(new BigDecimal("9.99"))))
                .andReturn().getResponse().getContentAsString();
        long buyOrderId = objectMapper.readTree(buyResponse).get("orderId").asLong();

        mockMvc.perform(get("/api/orders/{id}", buyOrderId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId", is((int) buyOrderId)));

        mockMvc.perform(get("/api/portfolio").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.krwBalance", comparesEqualTo(new BigDecimal("250000"))))
                .andExpect(jsonPath("$.tokenBalance", comparesEqualTo(new BigDecimal("9.99"))))
                .andExpect(jsonPath("$.averageBuyPrice", closeTo(75075.07507508, 0.00000001)));

        mockMvc.perform(post("/api/orders/sell")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"tokenAmount\":9.99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("FILLED")));

        mockMvc.perform(get("/api/portfolio").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.krwBalance", comparesEqualTo(new BigDecimal("998500.75"))))
                .andExpect(jsonPath("$.tokenBalance", closeTo(0, 0.00000001)))
                .andExpect(jsonPath("$.averageBuyPrice", closeTo(0, 0.00000001)));

        mockMvc.perform(get("/api/orders").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/trades").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void insufficientBalanceKeepsFailedOrderWithoutTrade() throws Exception {
        String token = signup("trader2");

        mockMvc.perform(post("/api/orders/buy")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INSUFFICIENT_BALANCE")));

        mockMvc.perform(get("/api/orders").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status", is("FAILED")));
        mockMvc.perform(get("/api/trades").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void usersCannotReadEachOthersOrders() throws Exception {
        String ownerToken = signup("owner1");
        String otherToken = signup("other1");
        mockMvc.perform(post("/api/wallet/faucet").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk());
        String response = mockMvc.perform(post("/api/orders/buy")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":75000}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(response).get("orderId").asLong();

        mockMvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ORDER_NOT_FOUND")));
    }

    @Test
    void rejectsZeroAmountAndUnsupportedSymbol() throws Exception {
        String token = signup("trader3");
        mockMvc.perform(post("/api/orders/buy")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

        mockMvc.perform(post("/api/orders/buy")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"BTC\",\"krwAmount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_SYMBOL")));
    }

    private String signup(String loginId) throws Exception {
        String response = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupBody(loginId, "password123", loginId))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private String bearer(String token) { return "Bearer " + token; }
    private record SignupBody(String loginId, String password, String nickname) {}
}
