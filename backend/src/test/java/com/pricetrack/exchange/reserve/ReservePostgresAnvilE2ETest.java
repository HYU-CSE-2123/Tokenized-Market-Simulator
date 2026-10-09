package com.pricetrack.exchange.reserve;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService;
import com.pricetrack.exchange.market.provider.simulated.SimulatedPriceProvider;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.VoidResponse;
import org.web3j.protocol.http.HttpService;

/** Only a freshly generated feature fixture on 25542/25545; never developer/acceptance services. */
@EnabledIfEnvironmentVariable(named="RESERVE_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @DirtiesContext
class ReservePostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry registry) throws Exception {
        Path runtime=Path.of(System.getenv("RESERVE_E2E_RUN")).toRealPath();
        Path expected=Path.of("../deployment/runtime").toRealPath();
        if(!runtime.getParent().equals(expected) || !runtime.getFileName().toString().matches("reserve-e2e-\\d{14}-[0-9a-f]{6}"))
            throw new IllegalStateException("Dedicated feature fixture required");
        Properties props=new Properties();
        try(var input=Files.newInputStream(runtime.resolve("secrets/test.properties"))) { props.load(input); }
        if(!props.getProperty("spring.datasource.url").startsWith("jdbc:postgresql://127.0.0.1:25542/exchange_reserve_e2e?")
                || !props.getProperty("app.blockchain.rpc-url").equals("http://127.0.0.1:25545")
                || !props.getProperty("app.price.provider").equals("simulated") || !props.getProperty("app.ai.enabled").equals("false"))
            throw new IllegalStateException("Unsafe fixture endpoint");
        props.forEach((k,v)->registry.add(k.toString(),()->v));
    }
    @Autowired TestRestTemplate http; @Autowired ReserveService reserve;
    @SpyBean ReserveStore store;
    @Autowired UserRepository users; @Autowired WalletService wallet; @Autowired UserBalanceRepository balances;
    @Autowired OrderRepository orders; @Autowired JdbcTemplate jdbc;
    @Autowired BlockchainReconciliationService reconciliation; @Autowired SimulatedPriceProvider prices;
    @Autowired ApplicationContext context;
    @SpyBean RpcReserveChainReader chainReader;
    @Test void signedBuySellImmutableAnchorTamperAndPendingAreIsolated() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("exchange_reserve_e2e");
        assertThat(context.getBeansOfType(com.pricetrack.exchange.market.provider.toss.TossAuthClient.class)).isEmpty();
        assertThat(context.getBeansOfType(com.pricetrack.exchange.ai.provider.OpenAiProvider.class)).isEmpty();
        assertThat(store.baseline()).isEmpty();
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            return call.callRealMethod();
        }).when(store).cut();
        var signup=post("/api/auth/signup",Map.of("loginId","reserve_fixture","password","fixture-only-password","nickname","대사테스트"),null,201);
        String token=signup.path("accessToken").asText();
        long user=jdbc.queryForObject("SELECT id FROM users WHERE login_id='reserve_fixture'",Long.class);
        var baseline=reserve.createBaseline(user);
        assertThatThrownBy(()->reserve.createBaseline(user)).hasMessageContaining("IMMUTABLE_BASELINE_EXISTS");
        for(String sql:List.of("UPDATE reserve_baselines SET actor_id=999","DELETE FROM reserve_baselines","TRUNCATE reserve_baselines"))
            assertThatThrownBy(()->jdbc.update(sql)).hasMessageContaining("append-only");
        post("/api/wallet/faucet",Map.of(),token,200);
        var funded=reserve.run(user); assertThat(funded.status()).isEqualTo("MATCH");
        assertThat(funded.liquidity().operatorEthWei()).matches("[0-9]+");
        assertThat(funded.liquidity().gasAssessment()).isEqualTo("READ_ONLY_NOT_ESTIMATED");
        prices.tick();
        var quote=post("/api/quotes/buy",Map.of("symbol","mSEC","krwAmount","100000"),token,200);
        var buy=post("/api/orders/buy",Map.of("symbol","mSEC","krwAmount","100000","quoteId",quote.path("quoteId").asText()),token,202);
        long buyId=buy.path("orderId").asLong();
        assertThat(orders.findById(buyId).orElseThrow().getExecutionMode()).isEqualTo(ExecutionMode.ONCHAIN);
        assertThat(reserve.run(user).status()).isEqualTo("INCONCLUSIVE");
        settle(buyId);
        var match=reserve.run(user); assertThat(match.status()).as(match.reason()).isEqualTo("MATCH");
        BigDecimal qty=balances.findByUserIdAndSymbol(user,"mSEC").orElseThrow().getAmount();
        prices.tick();
        var sellQuote=post("/api/quotes/sell",Map.of("symbol","mSEC","tokenAmount",qty.toPlainString()),token,200);
        var sell=post("/api/orders/sell",Map.of("symbol","mSEC","tokenAmount",qty.toPlainString(),"quoteId",sellQuote.path("quoteId").asText()),token,202);
        settle(sell.path("orderId").asLong());
        assertThat(reserve.run(user).status()).isEqualTo("MATCH");
        var before=store.cut();
        var head=context.getBean(ReserveChainReader.class).head();
        reserve.run(user);
        assertThat(store.cut().balances()).isEqualTo(before.balances());
        assertThat(store.cut().orders()).isEqualTo(before.orders());
        assertThat(context.getBean(ReserveChainReader.class).head()).isEqualTo(head);
        // Real separate JDBC writer commits after the first DB cut, while chain reads are gated in the test only.
        concurrentCutChange(user,()->wallet.faucet(user));
        assertThat(reserve.run(user).status()).isEqualTo("MATCH");
        long tradeId=jdbc.queryForObject("SELECT min(id) FROM trades",Long.class);
        var previous=jdbc.queryForObject("SELECT created_at FROM trades WHERE id=?",java.sql.Timestamp.class,tradeId);
        concurrentCutChange(user,()->jdbc.update("UPDATE trades SET created_at=created_at+INTERVAL '1 second' WHERE id=?",tradeId));
        assertThat(jdbc.queryForObject("SELECT created_at FROM trades WHERE id=?",java.sql.Timestamp.class,tradeId)).isAfter(previous);
        assertThat(reserve.run(user).status()).isEqualTo("MATCH");
        var beforeMine=chainReader.head();
        concurrentCutChange(user,()-> {
            var rpc=new HttpService("http://127.0.0.1:25545");
            var client=org.web3j.protocol.Web3j.build(rpc);
            try {
                var response=new Request<>("evm_mine",List.of(),rpc,VoidResponse.class).send();
                assertThat(response.hasError()).isFalse();
            } catch(java.io.IOException e) { throw new AssertionError("Dedicated fixture mining failed",e); }
            finally { client.shutdown(); }
        });
        assertThat(chainReader.head().number()).isGreaterThan(beforeMine.number());
        assertThat(reserve.run(user).status()).isEqualTo("MATCH");
        jdbc.update("UPDATE user_balances SET amount=amount+1 WHERE user_id=? AND symbol='mKRW'",user);
        assertThat(reserve.run(user).status()).isEqualTo("MISMATCH");
        // Deliberately retain the mismatch as evidence; never reset the anchor to hide it.
        assertThat(store.baseline()).contains(baseline);
        assertThatThrownBy(()->jdbc.update("DELETE FROM faucet_grants")).hasMessageContaining("append-only");
        assertThat(store.history()).hasSizeGreaterThanOrEqualTo(6);
    }
    private void concurrentCutChange(long user,Runnable change) throws Exception {
        var paused=new CountDownLatch(1); var resume=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        doAnswer(call->{
            var snapshot=call.callRealMethod();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            paused.countDown();
            if(!resume.await(10,TimeUnit.SECONDS)) throw new AssertionError("Cut barrier timeout");
            return snapshot;
        }).when(chainReader).read(any());
        try {
            var result=executor.submit(()->reserve.run(user));
            assertThat(paused.await(10,TimeUnit.SECONDS)).as("real RPC reader reached after DB cut").isTrue();
            change.run(); // commits on this thread before the reconciliation thread is released
            resume.countDown();
            var verdict=result.get(20,TimeUnit.SECONDS);
            assertThat(verdict.status()).isEqualTo("INCONCLUSIVE");
            assertThat(verdict.reason()).isEqualTo("CUT_CHANGED");
            assertThat(verdict.checks()).isEmpty(); assertThat(verdict.liquidity()).isNull();
        } finally {
            resume.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
            reset(chainReader);
        }
    }
    private void settle(long id) throws Exception {
        for(int i=0;i<100;i++) { reconciliation.reconcilePendingTransactions();
            if(orders.findById(id).orElseThrow().getStatus()==OrderStatus.FILLED) return; Thread.sleep(100); }
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
    }
    private JsonNode post(String path,Map<String,?> body,String token,int status) {
        var headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.setBearerAuth(token);
        var response=http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
        assertThat(response.getStatusCode().value()).as(path).isEqualTo(status); return response.getBody();
    }
}
