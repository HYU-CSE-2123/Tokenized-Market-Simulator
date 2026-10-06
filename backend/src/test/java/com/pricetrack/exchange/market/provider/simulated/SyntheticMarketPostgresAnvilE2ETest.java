package com.pricetrack.exchange.market.provider.simulated;

import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.provider.toss.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.trade.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.*;
import java.math.BigDecimal;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in. Dedicated DB and separate Anvil only; no production keys/state. */
@EnabledIfEnvironmentVariable(named = "SYNTHETIC_E2E_TESTS", matches = "true")
@ActiveProfiles("public")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/exchange_synthetic_test?connectTimeout=2&socketTimeout=10",
    "spring.datasource.username=exchange", "spring.datasource.password=exchange",
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect", "spring.sql.init.mode=always",
    "app.price.provider=simulated", "app.price.simulation.bootstrap-days=2",
    "app.price.update-interval-ms=1000", "app.price.initial-delay-ms=2147483647",
    "app.admin.password=", "app.ai.enabled=false", "app.ai.diagnosis.enabled=false",
    "app.blockchain.enabled=true", "app.blockchain.price-report.enabled=true",
    "app.blockchain.rpc-url=http://127.0.0.1:18545",
    "app.blockchain.mock-krw-address=0x5FbDB2315678afecb367f032d93F642f64180aa3",
    "app.blockchain.m-sec-address=0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512",
    "app.blockchain.price-oracle-address=0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0",
    "app.blockchain.exchange-vault-address=0xCf7Ed3AccA5a467e9e704C703E8D87F634fB0Fc9",
    "app.blockchain.operator-private-key=ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    "app.blockchain.price-report.signer-private-key=00000000000000000000000000000000000000000000000000000000000a11ce"
})
class SyntheticMarketPostgresAnvilE2ETest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @Autowired SimulatedPriceProvider prices;
    @Autowired BlockchainReconciliationService reconciliation;
    @Autowired OrderRepository orders;
    @Autowired TradeRepository trades;
    @Autowired UserBalanceRepository balances;
    @Autowired PriceQuoteRepository quotes;
    @LocalServerPort int port;

    @Test void publicHttpStompSignedBuySellAndPostgresCandlesAgree() throws Exception {
        assertThat(context.getBeansOfType(TossAuthClient.class)).isEmpty();
        assertThat(context.getBeansOfType(TossRealtimeClient.class)).isEmpty();
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        StompSession session = client.connectAsync("ws://127.0.0.1:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        try {
            var incoming = new CompletableFuture<JsonNode>();
            session.subscribe("/topic/markets/mSEC/price", new StompFrameHandler() {
                public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                public void handleFrame(StompHeaders headers, Object body) {
                    try { incoming.complete(json.readTree((byte[])body)); } catch (Exception failure) { incoming.completeExceptionally(failure); }
                }
            });
            // Subscription is asynchronous. Repeated REAL committed ticks, never fabricated events.
            for (int attempt = 0; attempt < 20 && !incoming.isDone(); attempt++) { prices.tick(); Thread.sleep(100); }
            var event = incoming.get(5, TimeUnit.SECONDS);
            assertThat(event.path("eventId").asText()).isNotBlank();
            assertThat(event.at("/data/provider").asText()).isEqualTo("SIMULATED");
            assertThat(event.at("/data/volume").decimalValue()).isPositive();
            for (String interval : List.of("1m", "5m", "15m", "30m", "1h", "1d")) {
                var page = get("/api/markets/mSEC/candles?interval=" + interval + "&count=2", null);
                assertThat(page.path("provider").asText()).isEqualTo("SIMULATED");
                assertThat(page.path("candles").size()).isPositive();
                assertThat(Instant.parse(page.path("asOf").asText()))
                        .isAfterOrEqualTo(Instant.parse(event.at("/data/observedAt").asText()));
            }
            String login = "synth_" + UUID.randomUUID().toString().substring(0, 8);
            String token = post("/api/auth/signup", Map.of("loginId", login, "password", "test-only-password", "nickname", "합성테스트"), null, 201)
                    .path("accessToken").asText();
            long user = get("/api/me", token).path("id").asLong();
            post("/api/wallet/faucet", Map.of(), token, 200);
            prices.tick();
            var buyQuote = post("/api/quotes/buy", Map.of("symbol", "mSEC", "krwAmount", "100000"), token, 200);
            assertThat(buyQuote.has("signature")).isFalse(); assertThat(buyQuote.has("executor")).isFalse();
            String quoteId = buyQuote.path("quoteId").asText();
            assertThat(quotes.findById(quoteId).orElseThrow().getUserId()).isEqualTo(user);
            var buy = post("/api/orders/buy", Map.of("symbol", "mSEC", "krwAmount", "100000", "quoteId", quoteId), token, 202);
            long buyId = buy.path("orderId").asLong(); settle(buyId);
            assertThat(trades.findAllByUserIdOrderByCreatedAtDesc(user).stream().filter(t -> t.getOrderId().equals(buyId)).findFirst().orElseThrow().getPrice()).isEqualByComparingTo(buyQuote.path("price").decimalValue());
            post("/api/orders/buy", Map.of("symbol", "mSEC", "krwAmount", "100000", "quoteId", quoteId), token, 409);
            BigDecimal quantity = balances.findByUserIdAndSymbol(user, "mSEC").orElseThrow().getAmount();
            prices.tick();
            var sellQuote = post("/api/quotes/sell", Map.of("symbol", "mSEC", "tokenAmount", quantity.toPlainString()), token, 200);
            var sell = post("/api/orders/sell", Map.of("symbol", "mSEC", "tokenAmount", quantity.toPlainString(), "quoteId", sellQuote.path("quoteId").asText()), token, 202);
            long sellId = sell.path("orderId").asLong(); settle(sellId);
            assertThat(trades.findAllByUserIdOrderByCreatedAtDesc(user).stream().filter(t -> t.getOrderId().equals(sellId)).findFirst().orElseThrow().getPrice()).isEqualByComparingTo(sellQuote.path("price").decimalValue());
            assertThat(balances.findByUserIdAndSymbol(user, "mSEC").orElseThrow().getAmount()).isZero();
            assertThat(balances.findByUserIdAndSymbol(user, "mKRW").orElseThrow().getLockedAmount()).isZero();
            assertThat(quotes.findById(quoteId).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.CONSUMED);
            assertThat(get("/api/portfolio", token).path("totalValue").isMissingNode()).isFalse();
            System.out.println("Synthetic PostgreSQL+Anvil E2E: HTTP/WS, 6 candles, signed BUY/SELL, quote reuse rejected, locks released");
        } finally { if (session.isConnected()) session.disconnect(); client.stop(); }
        // Dedicated test ledger retained for inspection; no deletion of existing user/chain data.
    }
    private void settle(long id) throws Exception {
        for (int i = 0; i < 100; i++) {
            reconciliation.reconcilePendingTransactions();
            if (orders.findById(id).orElseThrow().getStatus() == OrderStatus.FILLED) return;
            Thread.sleep(100);
        }
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
    }
    private JsonNode get(String path, String token) {
        var result = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200); return result.getBody();
    }
    private JsonNode post(String path, Map<String, ?> body, String token, int status) {
        var result = http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
        assertThat(result.getStatusCode().value()).as("%s: %s", path, result.getBody()).isEqualTo(status); return result.getBody();
    }
    private HttpHeaders headers(String token) { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); if (token != null) h.setBearerAuth(token); return h; }
}
