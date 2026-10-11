package com.pricetrack.exchange.conditional;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService;
import com.pricetrack.exchange.market.provider.simulated.SimulatedPriceProvider;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.reserve.ReserveService;
import com.pricetrack.exchange.tradeaudit.AuditService;
import com.pricetrack.exchange.wallet.UserBalanceRepository;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

/** Real fresh PG + Anvil. RPC response loss occurs AFTER the actual raw transaction is forwarded. */
@EnabledIfEnvironmentVariable(named="CONDITIONAL_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @DirtiesContext
@org.springframework.test.context.ActiveProfiles("conditional-fixture")
class ConditionalPostgresAnvilE2ETest {
    static HttpServer proxy; static ExecutorService proxyPool;
    static final AtomicBoolean loseSendResponse=new AtomicBoolean();
    static final AtomicInteger lost=new AtomicInteger();
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry registry) throws Exception {
        Path root=Path.of(System.getenv("CONDITIONAL_E2E_RUN")).toRealPath();
        if(!root.getParent().equals(Path.of("../deployment/runtime").toRealPath())
            || !root.getFileName().toString().matches("audit-e2e-\\d{14}-[0-9a-f]{6}"))throw new IllegalStateException("Dedicated fixture required");
        Properties p=new Properties();try(var in=Files.newInputStream(root.resolve("secrets/test.properties"))){p.load(in);}
        if(!p.getProperty("spring.datasource.url").startsWith("jdbc:postgresql://127.0.0.1:26542/exchange_trade_audit_e2e?")
            || !p.getProperty("app.blockchain.rpc-url").equals("http://127.0.0.1:26545")
            || !p.getProperty("app.price.provider").equals("simulated") || !p.getProperty("app.ai.enabled").equals("false"))throw new IllegalStateException("Unsafe fixture");
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);proxyPool=Executors.newFixedThreadPool(4);proxy.setExecutor(proxyPool);
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        proxy.createContext("/",exchange->{
            try {
                byte[] body=exchange.getRequestBody().readAllBytes();
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:26545"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
                if(loseSendResponse.get() && new String(body,java.nio.charset.StandardCharsets.UTF_8).contains("eth_sendRawTransaction")){
                    lost.incrementAndGet();return; // No response headers: real transport failure, not a mocked sender.
                }
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
            }catch(Exception failure){/* Fault details remain private; never log raw request bytes or keys. */}
            finally{exchange.close();}
        });proxy.start();
        p.setProperty("app.blockchain.rpc-url","http://127.0.0.1:"+proxy.getAddress().getPort());
        p.forEach((k,v)->registry.add(k.toString(),()->v));
        registry.add("app.conditional.enabled",()->"true");registry.add("app.conditional.fixture",()->"true");
        registry.add("app.conditional.initial-delay-ms",()->"2147483647");
    }
    @AfterAll static void close(){if(proxy!=null)proxy.stop(0);if(proxyPool!=null)proxyPool.shutdownNow();}
    @Autowired TestRestTemplate http;@Autowired JdbcTemplate jdbc;
    @Autowired ConditionalOrderService service;@Autowired ConditionalOrderWorker worker;
    @Autowired ConditionalOrderRepository intents;@Autowired BlockchainReconciliationService reconciliation;
    @Autowired SimulatedPriceProvider prices;@Autowired OrderRepository orders;
    @Autowired UserBalanceRepository balances;@Autowired ReserveService reserve;@Autowired AuditService audit;
    String token;long uid;

    @Test void actualAtomicExecutionRaceRecoveryAndAudit() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders",Long.class)).isZero();
        token=post("/api/auth/signup",Map.of("loginId","conditional_real","nickname","조건검증","password","fixture-password-only"),null,null,201).path("accessToken").asText();
        uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id='conditional_real'",Long.class);
        post("/api/admin/reserve-reconciliations/baseline",Map.of(),token,null,403);
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?",uid);
        token=post("/api/auth/login",Map.of("loginId","conditional_real","password","fixture-password-only"),null,null,200).path("accessToken").asText();
        post("/api/admin/reserve-reconciliations/baseline",Map.of(),token,null,201);
        post("/api/wallet/faucet",Map.of(),token,UUID.randomUUID().toString(),200);

        var waiting=create("BUY","100000","1");worker.evaluate(waiting);
        assertThat(service.find(uid,waiting).state()).isEqualTo(ConditionalOrder.State.WAITING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_quotes",Long.class)).isZero();
        post("/api/conditional-orders/"+waiting+"/cancel",Map.of(),token,null,200);

        String key=UUID.randomUUID().toString();var body=request("BUY","100000","1000000000");
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return post("/api/conditional-orders",body,token,key,201).path("id").asText();});
            var b=pool.submit(()->{start.await();return post("/api/conditional-orders",body,token,key,201).path("id").asText();});
            start.countDown();String buy=a.get(15,TimeUnit.SECONDS);assertThat(b.get(15,TimeUnit.SECONDS)).isEqualTo(buy);
            prices.tick();var run=new CountDownLatch(1);
            var x=pool.submit(()->{run.await();worker.evaluate(buy);return true;});var y=pool.submit(()->{run.await();worker.evaluate(buy);return true;});
            run.countDown();x.get(15,TimeUnit.SECONDS);y.get(15,TimeUnit.SECONDS);settle(buy);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders",Long.class)).isEqualTo(1);
            post("/api/conditional-orders/"+buy+"/cancel",Map.of(),token,null,409);

            var qty=balances.findByUserIdAndSymbol(uid,"mSEC").orElseThrow().getAmount().toPlainString();
            String sell=create("SELL",qty,"1");prices.tick();worker.evaluate(sell);settle(sell);
            assertThat(balances.findByUserIdAndSymbol(uid,"mSEC").orElseThrow().getAmount()).isZero();

            String race=create("BUY","10000","1000000000");prices.tick();var gate=new CountDownLatch(1);
            var execute=pool.submit(()->{gate.await();worker.evaluate(race);return true;});
            var cancel=pool.submit(()->{gate.await();return http.exchange("/api/conditional-orders/"+race+"/cancel",HttpMethod.POST,
                new HttpEntity<>(Map.of(),headers(token,null)),JsonNode.class).getStatusCode().value();});
            gate.countDown();execute.get(15,TimeUnit.SECONDS);int code=cancel.get(15,TimeUnit.SECONDS);
            assertThat(code).isIn(200,409);if(code==200){assertThat(service.find(uid,race).state()).isEqualTo(ConditionalOrder.State.CANCELLED);assertThat(service.find(uid,race).executionOrderId()).isNull();}
            else settle(race);
        }finally{pool.shutdownNow();}

        String recovered=create("BUY","20000","1000000000");prices.tick();loseSendResponse.set(true);
        try{worker.evaluate(recovered);}finally{loseSendResponse.set(false);}
        assertThat(lost.get()).isPositive();
        var linked=service.find(uid,recovered);assertThat(linked.executionOrderId()).isNotNull();
        assertThat(linked.state()).isEqualTo(ConditionalOrder.State.EXECUTING);
        assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isEqualByComparingTo("20000");
        long count=jdbc.queryForObject("SELECT count(*) FROM orders",Long.class);worker.evaluate(recovered);settle(recovered);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders",Long.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE order_id=?",Long.class,linked.executionOrderId())).isEqualTo(1);
        assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isZero();

        String restart=create("BUY","10000","1000000000");prices.tick();service.claim(restart,"previous-worker");prices.tick();worker.evaluate(restart);settle(restart);
        String expired=create("BUY","10000","1000000000");jdbc.update("UPDATE conditional_orders SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",expired);worker.evaluate(expired);
        assertThat(service.find(uid,expired).state()).isEqualTo(ConditionalOrder.State.EXPIRED);
        get("/api/admin/conditional-orders",token,200);get("/api/conditional-orders",token,200);
        assertThat(post("/api/admin/reserve-reconciliations",Map.of(),token,null,200).path("status").asText()).isEqualTo("MATCH");
        String run=post("/api/admin/trade-audits",Map.of(),token,null,202).path("id").asText();
        for(int i=0;i<250 && audit.detail(run).lifecycle().equals("RUNNING");i++)Thread.sleep(100);
        assertThat(audit.detail(run).verdict()).as(audit.detail(run).reason()).isEqualTo("MATCH");
        assertThat(audit.detail(run).coverage().chainComplete()).isTrue();

        // Real mined status=0: delay mining beyond the signed report's expiry; do not retry a new order.
        String rejected=create("BUY","10000","1000000000");prices.tick();rpc("anvil_setAutomine",List.of(false));
        try {
            worker.evaluate(rejected);long order=service.find(uid,rejected).executionOrderId();
            long expires=jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM valid_until)::bigint FROM price_quotes WHERE order_id=?",Long.class,order);
            rpc("evm_setNextBlockTimestamp",List.of(expires+1));rpc("evm_mine",List.of());
            for(int i=0;i<100 && service.find(uid,rejected).state()!=ConditionalOrder.State.FAILED;i++){
                reconciliation.reconcilePendingTransactions();service.refresh(rejected);Thread.sleep(100);
            }
            assertThat(service.find(uid,rejected).state()).isEqualTo(ConditionalOrder.State.FAILED);
            assertThat(orders.findById(order).orElseThrow().getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE order_id=?",Long.class,order)).isZero();
            assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isZero();
        }finally{rpc("anvil_setAutomine",List.of(true));}
    }
    String create(String side,String amount,String price){prices.tick();return post("/api/conditional-orders",request(side,amount,price),token,UUID.randomUUID().toString(),201).path("id").asText();}
    Map<String,?> request(String side,String amount,String price){return Map.of("symbol","mSEC","side",side,"inputAmount",amount,"targetPrice",price,"expiresAt",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());}
    void settle(String id) throws Exception {for(int i=0;i<100;i++){reconciliation.reconcilePendingTransactions();service.refresh(id);if(service.find(uid,id).state()==ConditionalOrder.State.SUCCEEDED)return;Thread.sleep(100);}throw new AssertionError("Conditional settlement not successful: "+service.find(uid,id).state()+" / "+service.find(uid,id).reasonCode());}
    HttpHeaders headers(String auth,String key){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(auth!=null)h.setBearerAuth(auth);if(key!=null)h.set("Idempotency-Key",key);return h;}
    JsonNode post(String path,Map<String,?> body,String auth,String key,int expected){var r=http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers(auth,key)),JsonNode.class);assertThat(r.getStatusCode().value()).as(path+" "+r.getBody()).isEqualTo(expected);return r.getBody();}
    JsonNode get(String path,String auth,int expected){var r=http.exchange(path,HttpMethod.GET,new HttpEntity<>(headers(auth,null)),JsonNode.class);assertThat(r.getStatusCode().value()).as(path).isEqualTo(expected);return r.getBody();}
    void rpc(String method,List<?> args) throws Exception {
        var rpc=new org.web3j.protocol.http.HttpService("http://127.0.0.1:26545");
        try{
            assertThat(new org.web3j.protocol.core.Request<>(method,args,rpc,org.web3j.protocol.core.Response.class).send().hasError()).as(method).isFalse();
        }finally{rpc.close();}
    }
}
