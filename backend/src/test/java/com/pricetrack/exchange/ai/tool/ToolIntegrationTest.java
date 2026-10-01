package com.pricetrack.exchange.ai.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.trade.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.ai.tools.enabled=true", "app.ai.enabled=false", "app.blockchain.enabled=false",
        "app.blockchain.price-report.enabled=false", "app.admin.password=", "spring.datasource.url=jdbc:h2:mem:tool_tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ToolIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired PriceQuoteRepository quotes;
    @Autowired BlockchainTransactionRepository transactions;
    @Autowired TradeRepository trades;
    @Autowired UserBalanceRepository balances;
    @Autowired WalletService wallets;
    @Autowired OrderService trading;
    @Autowired ToolReadFacade reads;
    @Autowired ToolDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;
    @MockBean ReceiptReader receipts;
    User alice, bob, admin;
    Order order;

    @BeforeEach void fixtures() {
        trades.deleteAll(); quotes.deleteAll(); transactions.deleteAll(); orders.deleteAll(); balances.deleteAll(); users.deleteAll();
        alice = user("tool-alice", UserRole.USER); bob = user("tool-bob", UserRole.USER); admin = user("tool-admin", UserRole.ADMIN);
        order = orders.saveAndFlush(ToolFixtures.order(alice.getId()));
        quotes.saveAndFlush(ToolFixtures.quote(alice.getId(), order.getId()));
        transactions.saveAndFlush(ToolFixtures.tx(order.getId()));
        UserBalance krw = new UserBalance(alice.getId(), "mKRW"); krw.setAmount(new BigDecimal("100000")); krw.setLockedAmount(new BigDecimal("75000"));
        UserBalance sec = new UserBalance(alice.getId(), "mSEC"); sec.setAmount(new BigDecimal("2")); sec.setAverageBuyPrice(new BigDecimal("70000"));
        balances.saveAllAndFlush(List.of(krw, sec));
        reset(receipts);
        when(receipts.read(any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return json.createObjectNode().put("receiptStatus", "NOT_FOUND");
        });
    }
    User user(String id, UserRole role) {
        User u = new User(); u.setLoginId(id); u.setNickname(id); u.setRole(role); u.setPasswordHash("SENSITIVE_PASSWORD_HASH");
        return users.saveAndFlush(u);
    }
    String token(User user) { return jwt.createToken(user.getId(), user.getLoginId()); }
    JsonNode call(User user, String tool, String args, int status) throws Exception {
        String body = mvc.perform(post("/api/ai/tools/" + tool).header("Authorization", "Bearer " + token(user))
                .contentType("application/json").content("{\"arguments\":" + args + "}"))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
    String orderArgs() { return "{\"orderId\":" + order.getId() + "}"; }
    String quoteArgs() { return "{\"quoteId\":\"" + ToolFixtures.QUOTE + "\"}"; }

    @Test void allEightToolsExecuteWithoutRagOrAiDatabase() throws Exception {
        for (String tool : List.of("getOrder", "getBlockchainTransaction", "getReceiptSummary"))
            assertThat(call(alice, tool, orderArgs(), 200).path("status").asText()).isEqualTo("SUCCESS");
        call(alice, "getQuote", quoteArgs(), 200);
        for (String tool : List.of("getMarketStatus", "getCurrentReferencePrice", "getPortfolio")) call(alice, tool, "{}", 200);
        assertThat(call(admin, "listAbnormalOrders", "{}", 200).path("data").path("items").size()).isEqualTo(1);
    }
    @Test void foreignAndMissingResourcesAreIndistinguishableAndDoNotReachRpc() throws Exception {
        for (String tool : List.of("getOrder", "getBlockchainTransaction", "getReceiptSummary")) {
            assertThat(call(bob, tool, orderArgs(), 404).path("error").asText()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(call(bob, tool, "{\"orderId\":9223372036854775807}", 404).path("error").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(call(bob, "getQuote", quoteArgs(), 404).path("error").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        verifyNoInteractions(receipts);
    }
    @Test void adminScopeAndPortfolioOwnershipAreEnforced() throws Exception {
        call(alice, "listAbnormalOrders", "{}", 403);
        call(admin, "getOrder", orderArgs(), 200); call(admin, "getQuote", quoteArgs(), 200);
        call(admin, "getBlockchainTransaction", orderArgs(), 200); call(admin, "getReceiptSummary", orderArgs(), 200);
        assertThat(call(admin, "getPortfolio", "{}", 200).path("data").path("balances").get(0).path("total").asText()).isEqualTo("0");
        call(admin, "getPortfolio", "{\"userId\":" + alice.getId() + "}", 400);
        assertThatThrownBy(() -> reads.abnormal(ToolContext.from(new AuthenticatedUser(alice.getId(), "ignored", UserRole.USER)),
                new ToolRegistry.Arguments(null, null, 10, "REVIEW_REQUIRED", 300, null)))
                .isInstanceOf(ToolFailure.class).hasMessage("TOOL_FORBIDDEN");
        assertThatThrownBy(() -> reads.order(null, order.getId())).isInstanceOf(ToolFailure.class);
    }
    @Test void readsAreImmutableAndNeverExposeSecretsOrGeneralTxHash() throws Exception {
        var before = snapshot();
        for (String tool : List.of("getOrder", "getQuote", "getBlockchainTransaction", "getPortfolio", "listAbnormalOrders")) {
            String args = tool.equals("getQuote") ? quoteArgs() : List.of("getOrder", "getBlockchainTransaction").contains(tool) ? orderArgs() : "{}";
            String output = call(tool.equals("listAbnormalOrders") ? admin : alice, tool, args, 200).toString();
            assertThat(output).doesNotContain("SENSITIVE", "rawTransaction", "signature", "executor", "password", "loginId", "senderAddress", "nonce");
            if (!tool.equals("getBlockchainTransaction")) assertThat(output).doesNotContain(ToolFixtures.HASH);
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(quotes.findById(ToolFixtures.QUOTE).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.ISSUED);
        assertThat(call(alice, "getQuote", quoteArgs(), 200).path("data").path("expiredByTime").asBoolean()).isTrue();
    }
    @Test void actualTradeAndExpectedOutputRemainDistinct() throws Exception {
        trades.saveAndFlush(ToolFixtures.trade(alice.getId(), order.getId()));
        var data = call(alice, "getOrder", orderArgs(), 200).path("data");
        assertThat(new BigDecimal(data.path("expectedOutputAmount").asText())).isEqualByComparingTo("0.999");
        assertThat(new BigDecimal(data.path("trade").path("price").asText())).isEqualByComparingTo("75000");
        assertThat(data.toString()).doesNotContain(ToolFixtures.HASH);
    }
    @Test void storedConsumedQuoteDoesNotBecomeExpiredOnRead() throws Exception {
        var quote = quotes.findById(ToolFixtures.QUOTE).orElseThrow(); quote.setStatus(PriceQuoteStatus.CONSUMED); quotes.saveAndFlush(quote);
        var data = call(alice, "getQuote", quoteArgs(), 200).path("data");
        assertThat(data.path("storedStatus").asText()).isEqualTo("CONSUMED"); assertThat(data.path("expiredByTime").asBoolean()).isTrue();
        assertThat(quotes.findById(ToolFixtures.QUOTE).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.CONSUMED);
    }
    @Test void sellKeepsTokenInputKrwOutputAndReceiptDirectionWithoutWrites() throws Exception {
        order.setSide(OrderSide.SELL); order.setInputAmount(new BigDecimal("2"));
        order.setExpectedOutputAmount(new BigDecimal("149850")); orders.saveAndFlush(order);
        var quote = quotes.findById(ToolFixtures.QUOTE).orElseThrow();
        quote.setSide(com.pricetrack.exchange.blockchain.oracle.PriceReport.Side.SELL);
        quote.setInputAmount(com.pricetrack.exchange.blockchain.support.TokenUnits.toWei(new BigDecimal("2")));
        quote.setMinimumOutput(com.pricetrack.exchange.blockchain.support.TokenUnits.toWei(new BigDecimal("149850")));
        quote.setFee(com.pricetrack.exchange.blockchain.support.TokenUnits.toWei(new BigDecimal("150")));
        quotes.saveAndFlush(quote);
        var tx = transactions.findByOrderId(order.getId()).orElseThrow();
        tx.setType(BlockchainTransactionType.SELL); transactions.saveAndFlush(tx);
        var trade = ToolFixtures.trade(alice.getId(), order.getId()); trade.setSide(OrderSide.SELL);
        trade.setBaseAmount(new BigDecimal("2")); trade.setQuoteAmount(new BigDecimal("149850"));
        trade.setFee(new BigDecimal("150")); trades.saveAndFlush(trade);
        var before = snapshot();
        var result = call(alice, "getOrder", orderArgs(), 200).path("data");
        var quoted = call(alice, "getQuote", quoteArgs(), 200).path("data");
        for (var data : List.of(result, quoted)) {
            assertThat(data.path("side").asText()).isEqualTo("SELL");
            assertThat(data.path("inputSymbol").asText()).isEqualTo("mSEC");
            assertThat(data.path("outputSymbol").asText()).isEqualTo("mKRW");
            assertThat(new BigDecimal(data.path("inputAmount").asText())).isEqualByComparingTo("2");
        }
        assertThat(new BigDecimal(result.path("expectedOutputAmount").asText())).isEqualByComparingTo("149850");
        assertThat(new BigDecimal(quoted.path("minimumOutputAmount").asText())).isEqualByComparingTo("149850");
        assertThat(new BigDecimal(quoted.path("fee").asText())).isEqualByComparingTo("150");
        assertThat(quoted.path("feeSymbol").asText()).isEqualTo("mKRW");
        assertThat(result.path("trade").path("baseSymbol").asText()).isEqualTo("mSEC");
        assertThat(result.path("trade").path("quoteSymbol").asText()).isEqualTo("mKRW");
        assertThat(new BigDecimal(result.path("trade").path("baseAmount").asText())).isEqualByComparingTo("2");
        assertThat(new BigDecimal(result.path("trade").path("quoteAmount").asText())).isEqualByComparingTo("149850");
        call(alice, "getReceiptSummary", orderArgs(), 200);
        verify(receipts).read(argThat(input -> input.type() == BlockchainTransactionType.SELL
                && input.expectedInput().equals(quote.getInputAmount())));
        assertThat(snapshot()).isEqualTo(before);
        reset(receipts);
        call(bob, "getReceiptSummary", orderArgs(), 404); verifyNoInteractions(receipts);
        tx.setType(BlockchainTransactionType.BUY); transactions.saveAndFlush(tx);
        assertThat(call(alice, "getReceiptSummary", orderArgs(), 503).path("error").asText()).isEqualTo("INCONSISTENT_LINK");
        verifyNoInteractions(receipts);
    }
    @Test void corruptedQuoteLinkDoesNotDiscloseAnotherUsersOrder() throws Exception {
        var other = orders.saveAndFlush(ToolFixtures.order(bob.getId()));
        var quote = quotes.findById(ToolFixtures.QUOTE).orElseThrow(); quote.setOrderId(other.getId()); quotes.saveAndFlush(quote);
        var data = call(alice, "getQuote", quoteArgs(), 200).path("data");
        assertThat(data.path("orderLink").asText()).isEqualTo("INCONSISTENT_LINK"); assertThat(data.path("orderId").isNull()).isTrue();
    }
    @Test void linkedAbsenceIsNotInventedAsFailureOrMockMode() throws Exception {
        quotes.deleteAll(); transactions.deleteAll();
        var data = call(alice, "getBlockchainTransaction", orderArgs(), 200).path("data");
        assertThat(data.path("linkStatus").asText()).isEqualTo("NOT_LINKED"); assertThat(data.toString()).doesNotContain("FAILED", "MOCK");
    }
    @Test void portfolioSeparatesLockedAndAvailableAndUsesOneReference() throws Exception {
        var data = call(alice, "getPortfolio", "{}", 200).path("data");
        var krw = data.path("balances").get(0);
        assertThat(new BigDecimal(krw.path("total").asText())).isEqualByComparingTo("100000");
        assertThat(new BigDecimal(krw.path("locked").asText())).isEqualByComparingTo("75000");
        assertThat(new BigDecimal(krw.path("available").asText())).isEqualByComparingTo("25000");
        assertThat(data.path("reference").path("observedAt").asText()).isNotBlank();
    }
    @Test void boundedPagingUsesBothTimestampAndIdWithoutDuplicates() throws Exception {
        var second = orders.saveAndFlush(ToolFixtures.order(bob.getId()));
        var tx = ToolFixtures.tx(second.getId()); tx.setTxHash("0x" + "dd".repeat(32)); tx.setNonce(11L); transactions.saveAndFlush(tx);
        var first = call(admin, "listAbnormalOrders", "{\"limit\":1}", 200).path("data");
        String cursor = first.path("nextCursor").asText();
        var next = call(admin, "listAbnormalOrders", "{\"limit\":1,\"cursor\":\"" + cursor + "\"}", 200).path("data");
        assertThat(first.path("items").get(0).path("orderId")).isNotEqualTo(next.path("items").get(0).path("orderId"));
        assertThat(next.path("nextCursor").isNull()).isTrue();
    }
    @Test void waitingLongIsSeparateFromReviewAndSystemTransactionsAreExcluded() throws Exception {
        var tx = transactions.findByOrderId(order.getId()).orElseThrow(); tx.setStatus(BlockchainTransactionStatus.SUBMITTED); transactions.saveAndFlush(tx);
        assertThat(call(admin, "listAbnormalOrders", "{}", 200).path("data").path("items").isEmpty()).isTrue();
        assertThat(call(admin, "listAbnormalOrders", "{\"filter\":\"WAITING_LONG\"}", 200).path("data").path("items").size()).isEqualTo(1);
        tx.setOrderId(null); tx.setType(BlockchainTransactionType.UPDATE_PRICE); tx.setStatus(BlockchainTransactionStatus.REVIEW_REQUIRED); transactions.saveAndFlush(tx);
        assertThat(call(admin, "listAbnormalOrders", "{}", 200).path("data").path("items").isEmpty()).isTrue();
    }
    @Test void jwtAnonymousExpiredAndTamperedTokensDoNotEnterTool() throws Exception {
        mvc.perform(post("/api/ai/tools/getOrder").content("{}" )).andExpect(status().isUnauthorized());
        String expired = new JwtTokenProvider("test-secret-test-secret-test-secret-32bytes", -1000).createToken(alice.getId(), "alice");
        for (String value : List.of(expired, token(alice) + "broken")) mvc.perform(post("/api/ai/tools/getOrder")
                .header("Authorization", "Bearer " + value).content("{}" )).andExpect(status().isUnauthorized());
        verifyNoInteractions(receipts);
    }
    @Test void ragAndUnknownAiPathsRemainAdminOnly() throws Exception {
        for (String endpoint : List.of("index", "search", "answers", "other", "tools/nested/path"))
            mvc.perform(post("/api/ai/" + endpoint).header("Authorization", "Bearer " + token(alice)).content("{}"))
                    .andExpect(status().isForbidden());
        call(alice, "forceFill", "{}", 404);
    }
    @Test void rejectsInjectedFieldsMalformedIdsDuplicateKeysAndOversizedBody() throws Exception {
        for (String args : List.of("{\"orderId\":0}", "{\"orderId\":1.0}", "{\"orderId\":9223372036854775808}",
                "{\"orderId\":1,\"role\":\"ADMIN\"}", "{\"orderId\":1,\"txHash\":\"bad\"}", "{}", "null"))
            call(alice, "getOrder", args, 400);
        call(alice, "getQuote", "{\"quoteId\":\"bad\"}", 400);
        for (String args : List.of("{\"limit\":21}", "{\"cursor\":\"bad\"}", "{\"filter\":\"FAILED\"}", "{\"waitingSeconds\":59}"))
            call(admin, "listAbnormalOrders", args, 400);
        for (String body : List.of("{\"arguments\":{},\"arguments\":{}}", "{\"arguments\":{}} {}", "{\"arguments\":{},\"role\":\"ADMIN\"}", "[", " ".repeat(2049)))
            mvc.perform(post("/api/ai/tools/getMarketStatus").header("Authorization", "Bearer " + token(alice))
                    .content(body)).andExpect(status().isBadRequest());
    }
    @Test void rpcFailureDoesNotInventFactsOrBlockMockTrading() throws Exception {
        when(receipts.read(any())).thenThrow(new ToolFailure("TOOL_TIMEOUT"));
        var result = call(alice, "getReceiptSummary", orderArgs(), 504);
        assertThat(result.path("data").isNull()).isTrue(); assertThat(result.path("error").asText()).isEqualTo("TOOL_TIMEOUT");
        wallets.initializeBalances(bob.getId()); wallets.faucet(bob.getId());
        assertThat(trading.buy(bob.getId(), "mSEC", new BigDecimal("1000"), null).getStatus()).isEqualTo(OrderStatus.FILLED);
    }
    Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("orders", "price_quotes", "blockchain_transactions", "trades", "user_balances"))
            result.put(table, jdbc.queryForList("select * from " + table + " order by " + (table.equals("price_quotes") ? "quote_id" : "id")));
        return result;
    }
}
