package com.pricetrack.exchange.ai.tool;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.trade.*;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.wallet.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses a dedicated database and deletes only rows created by this test. Never targets the trading database. */
@EnabledIfEnvironmentVariable(named = "AI_TOOL_POSTGRES_TESTS", matches = "true")
@SpringBootTest(properties = {"app.ai.tools.enabled=true", "app.ai.enabled=false", "app.blockchain.enabled=false",
        "app.blockchain.price-report.enabled=false", "app.admin.password=",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/exchange_tool_test?connectTimeout=2&socketTimeout=5",
        "spring.datasource.username=exchange", "spring.datasource.password=exchange", "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect", "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=always"})
class ToolPostgresIntegrationTest {
    @Autowired ToolDispatcher tools;
    @Autowired OrderRepository orders;
    @Autowired PriceQuoteRepository quotes;
    @Autowired BlockchainTransactionRepository transactions;
    @Autowired TradeRepository trades;
    @Autowired UserBalanceRepository balances;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    long uid;
    com.pricetrack.exchange.order.Order order;
    String quoteId;
    Long transactionId, balanceId, tradeId;
    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("exchange_tool_test");
        uid = Math.abs(System.nanoTime());
        order = orders.saveAndFlush(ToolFixtures.order(uid));
        var q = ToolFixtures.quote(uid, order.getId()); quoteId = "0x" + UUID.randomUUID().toString().replace("-", "").repeat(2); q.setQuoteId(quoteId); quotes.saveAndFlush(q);
        var t = ToolFixtures.tx(order.getId()); t.setTxHash("0x" + UUID.randomUUID().toString().replace("-", "").repeat(2)); t.setNonce(uid);
        transactionId = transactions.saveAndFlush(t).getId();
        var b = new UserBalance(uid, "mKRW"); b.setAmount(new BigDecimal("90000")); b.setLockedAmount(new BigDecimal("75000"));
        balanceId = balances.saveAndFlush(b).getId();
        tradeId = trades.saveAndFlush(ToolFixtures.trade(uid, order.getId())).getId();
    }
    @AfterEach void cleanup() {
        if (tradeId != null) trades.deleteById(tradeId);
        if (quoteId != null) quotes.deleteById(quoteId);
        if (transactionId != null) transactions.deleteById(transactionId);
        if (balanceId != null) balances.deleteById(balanceId);
        if (order != null) orders.deleteById(order.getId());
    }
    ToolResult call(String name, String args) {
        return tools.invoke(new AuthenticatedUser(uid, "unused", UserRole.USER), name,
                ("{\"arguments\":" + args + "}").getBytes(StandardCharsets.UTF_8));
    }
    @Test void postgresReadsKeepEveryPersistedFieldAndRowsUnchanged() {
        var before = snapshot();
        for (String name : List.of("getOrder", "getBlockchainTransaction"))
            assertThat(call(name, "{\"orderId\":" + order.getId() + "}").status()).isEqualTo("SUCCESS");
        var quote = call("getQuote", "{\"quoteId\":\"" + quoteId + "\"}");
        assertThat(quote.status()).isEqualTo("SUCCESS");
        assertThat(quote.data().path("storedStatus").asText()).isEqualTo("ISSUED");
        assertThat(quote.data().path("expiredByTime").asBoolean()).isTrue();
        assertThat(call("getPortfolio", "{}").status()).isEqualTo("SUCCESS");
        assertThat(call("getReceiptSummary", "{\"orderId\":" + order.getId() + "}").error()).isEqualTo("BLOCKCHAIN_DISABLED");
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void blockedSelectIsCancelledAndTransactionAndConnectionRecover() throws Exception {
        var before = snapshot();
        try (Connection locker = datasource.getConnection()) {
            locker.setAutoCommit(false);
            try {
                locker.createStatement().execute("LOCK TABLE orders IN ACCESS EXCLUSIVE MODE");
                long start = System.nanoTime();
                var result = call("getOrder", "{\"orderId\":" + order.getId() + "}");
                assertThat(result.error()).isEqualTo("TOOL_TIMEOUT"); assertThat(result.data()).isNull();
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid()", Integer.class)).isZero();
            } finally { locker.rollback(); }
        }
        assertThat(call("getOrder", "{\"orderId\":" + order.getId() + "}").status()).isEqualTo("SUCCESS");
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void agentStateUsesRealDispatcherAndDoesNotChangePostgresRows() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var provider=org.mockito.Mockito.mock(com.pricetrack.exchange.ai.agent.AgentModelProvider.class);
        org.mockito.Mockito.when(provider.plan(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
            .thenReturn(new com.pricetrack.exchange.ai.agent.AgentModelProvider.Plan(
                com.pricetrack.exchange.ai.agent.AgentModelProvider.Route.STATE,com.pricetrack.exchange.ai.agent.AgentModelProvider.Subject.ORDER,
                com.pricetrack.exchange.ai.agent.AgentModelProvider.Usage.none()));
        var before=snapshot();
        try(var agent=new com.pricetrack.exchange.ai.agent.AgentService(new com.pricetrack.exchange.ai.agent.AgentProperties(true),()->provider,()->null,tools,json)){
            var result=agent.answer(new AuthenticatedUser(uid,"unused",UserRole.USER),json.writeValueAsBytes(Map.of("question","현재 주문 상태","target",Map.of("orderId",order.getId()))));
            assertThat(result.status()).isEqualTo("ANSWERED");assertThat(result.metrics().toolCalls()).isEqualTo(1);
            assertThat(snapshot()).isEqualTo(before);
        }
    }
    Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", jdbc.queryForList("select * from orders where id=?", order.getId()));
        result.put("quotes", jdbc.queryForList("select * from price_quotes where quote_id=?", quoteId));
        result.put("transactions", jdbc.queryForList("select * from blockchain_transactions where id=?", transactionId));
        result.put("trades", jdbc.queryForList("select * from trades where id=?", tradeId));
        result.put("balances", jdbc.queryForList("select * from user_balances where user_id=?", uid));
        return result;
    }
}
