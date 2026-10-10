package com.pricetrack.exchange.tradeaudit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.reserve.ReserveService;
import com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService;
import com.pricetrack.exchange.market.provider.simulated.SimulatedPriceProvider;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.wallet.UserBalanceRepository;
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

/** Fresh dedicated PG+Anvil only; no developer/acceptance endpoints or paid providers. */
@EnabledIfEnvironmentVariable(named="AUDIT_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @DirtiesContext
class TradeAuditPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry registry) throws Exception {
        Path root=Path.of(System.getenv("AUDIT_E2E_RUN")).toRealPath();
        if(!root.getParent().equals(Path.of("../deployment/runtime").toRealPath()) || !root.getFileName().toString().matches("audit-e2e-\\d{14}-[0-9a-f]{6}")) throw new IllegalStateException("Dedicated audit fixture required");
        Properties p=new Properties(); try(var in=Files.newInputStream(root.resolve("secrets/test.properties"))) { p.load(in); }
        if(!p.getProperty("spring.datasource.url").startsWith("jdbc:postgresql://127.0.0.1:26542/exchange_trade_audit_e2e?") || !p.getProperty("app.blockchain.rpc-url").equals("http://127.0.0.1:26545") || !p.getProperty("app.price.provider").equals("simulated") || !p.getProperty("app.ai.enabled").equals("false")) throw new IllegalStateException("Unsafe fixture");
        p.forEach((k,v)->registry.add(k.toString(),()->v));
    }
    @Autowired TestRestTemplate http; @Autowired JdbcTemplate jdbc; @Autowired ReserveService reserve;
    @Autowired AuditService audit; @SpyBean AuditStore store; @SpyBean AuditValidator validator;
    @Autowired BlockchainReconciliationService reconciliation; @Autowired OrderRepository orders;
    @Autowired UserBalanceRepository balances; @Autowired SimulatedPriceProvider prices;
    @Autowired ApplicationContext context;
    private String admin;
    @Test void completeReadOnlyAuditAndConcurrency() throws Exception {
        assertThat(context.getBeansOfType(com.pricetrack.exchange.ai.provider.OpenAiProvider.class)).isEmpty();
        String user=post("/api/auth/signup",Map.of("loginId","audit_fixture","password","fixture-only-password","nickname","감사테스트"),null,201).path("accessToken").asText();
        long uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id='audit_fixture'",Long.class);
        post("/api/admin/trade-audits",Map.of(),null,401); post("/api/admin/trade-audits",Map.of(),user,403);
        reserve.createBaseline(uid);
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?",uid);
        admin=post("/api/auth/login",Map.of("loginId","audit_fixture","password","fixture-only-password"),null,200).path("accessToken").asText();
        post("/api/wallet/faucet",Map.of(),admin,200); prices.tick();
        var quote=post("/api/quotes/buy",Map.of("symbol","mSEC","krwAmount","100000"),admin,200);
        long buy=post("/api/orders/buy",Map.of("symbol","mSEC","krwAmount","100000","quoteId",quote.path("quoteId").asText()),admin,202).path("orderId").asLong(); settle(buy);
        var qty=balances.findByUserIdAndSymbol(uid,"mSEC").orElseThrow().getAmount().toPlainString(); prices.tick();
        var sellQuote=post("/api/quotes/sell",Map.of("symbol","mSEC","tokenAmount",qty),admin,200);
        long sell=post("/api/orders/sell",Map.of("symbol","mSEC","tokenAmount",qty,"quoteId",sellQuote.path("quoteId").asText()),admin,202).path("orderId").asLong(); settle(sell);
        doAnswer(c->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            return c.callRealMethod();
        }).when(store).cut();
        var before=store.cut(); String fp=store.fingerprint(before);
        var match=run(); assertThat(match.verdict()).as(match.reason()).isEqualTo("MATCH");
        assertThat(match.match()).isEqualTo(2); assertThat(match.coverage().scannedEvents()).isEqualTo(2);
        assertThat(match.coverage().chainComplete()).isTrue(); assertThat(store.fingerprint(store.cut())).isEqualTo(fp);
        var items=store.items(match.id(),0,null,null,null,null).items();
        assertThat(items).allSatisfy(i->{assertThat(i.verdict()).isEqualTo("MATCH");assertThat(i.checks()).anyMatch(c->c.code().equals("HISTORICAL_FEE") && c.matches());assertThat(i.checks()).anyMatch(c->c.code().equals("ORACLE_QUOTE_ID") && c.matches());});
        String payload=store.encode(match); assertThat(payload).doesNotContain(before.quotes().getFirst().signature(),before.transactions().getFirst().raw());
        assertThat(store.history(null).items()).isNotEmpty(); assertThat(store.items(match.id(),0,"MATCH","ONCHAIN","SELL",sell).items()).hasSize(1);
        rotateCurrentSettings(); assertThat(run().verdict()).isEqualTo("MATCH");
        jdbc.update("UPDATE price_quotes SET order_id=NULL WHERE order_id=?",buy);
        var unlinked=run(); assertThat(unlinked.verdict()).isEqualTo("INCONCLUSIVE");
        assertThat(store.items(unlinked.id(),0,"MISMATCH",null,null,null).items()).anyMatch(i->i.reason().equals("DB_QUOTE_WITHOUT_ORDER"));
        jdbc.update("UPDATE price_quotes SET order_id=? WHERE quote_id=?",buy,quote.path("quoteId").asText());assertThat(run().verdict()).isEqualTo("MATCH");
        // Source bounds include raw bytes despite JsonIgnore; only this fresh fixture is modified.
        for(String size:List.of("SINGLE","CUMULATIVE")) {
            if(size.equals("SINGLE")) jdbc.update("UPDATE blockchain_transactions SET raw_transaction=repeat('a',16000001) WHERE order_id=?",buy);
            else jdbc.update("UPDATE blockchain_transactions SET raw_transaction=repeat('b',8000001) WHERE type IN ('BUY','SELL')");
            var limited=run();assertThat(limited.verdict()).isEqualTo("INCONCLUSIVE");assertThat(limited.reason()).isEqualTo("DB_SNAPSHOT_LIMIT");assertThat(limited.coverage().dbComplete()).isFalse();
            for(var tx:before.transactions()) jdbc.update("UPDATE blockchain_transactions SET raw_transaction=? WHERE id=?",tx.raw(),tx.id());
            assertThat(run().verdict()).isEqualTo("MATCH");
        }
        for(String table:List.of("trade_audit_runs","trade_audit_results","trade_audit_items"))
            assertThatThrownBy(()->jdbc.update("DELETE FROM "+table)).hasMessageContaining("append-only");
        concurrent(()->jdbc.update("UPDATE trades SET price=price+1 WHERE order_id=?",buy));
        jdbc.update("UPDATE trades SET price=price-1 WHERE order_id=?",buy); assertThat(run().verdict()).isEqualTo("MATCH");
        concurrent(()->rpc("evm_mine")); assertThat(run().verdict()).isEqualTo("MATCH");
        jdbc.update("UPDATE trades SET fee=fee+1 WHERE order_id=?",buy);
        var mismatch=run(); assertThat(mismatch.verdict()).isEqualTo("MISMATCH");
        assertThat(store.items(mismatch.id(),0,"MISMATCH",null,null,null).items()).anySatisfy(i->assertThat(i.checks()).anyMatch(c->c.code().equals("EVENT_FEE") && !c.matches()));
        jdbc.update("UPDATE trades SET fee=fee-1 WHERE order_id=?",buy);
        jdbc.update("UPDATE orders SET execution_mode='UNKNOWN' WHERE id=?",buy);
        assertThat(run().verdict()).isEqualTo("INCONCLUSIVE"); jdbc.update("UPDATE orders SET execution_mode='ONCHAIN' WHERE id=?",buy);
        // Fixture-only delete proves reverse scanning catches a DB-erased successful chain trade.
        jdbc.update("DELETE FROM price_quotes WHERE order_id=?",sell); jdbc.update("DELETE FROM trades WHERE order_id=?",sell);
        jdbc.update("DELETE FROM blockchain_transactions WHERE order_id=?",sell); jdbc.update("DELETE FROM orders WHERE id=?",sell);
        var orphan=run(); assertThat(orphan.verdict()).isEqualTo("MISMATCH");
        assertThat(store.items(orphan.id(),0,"MISMATCH",null,null,null).items()).anyMatch(i->i.reason().equals("CHAIN_EVENT_WITHOUT_DB_SETTLEMENT"));
        assertThat(reserve.baseline()).isPresent();
    }
    private void concurrent(Runnable mutation) throws Exception {
        var paused=new CountDownLatch(1); var resume=new CountDownLatch(1);
        doAnswer(c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();paused.countDown();if(!resume.await(10,TimeUnit.SECONDS)) throw new AssertionError();return c.callRealMethod();}).when(validator).inspect(any(),anyList(),anyList(),anyList(),any(),any(),any(),anyInt());
        String id=post("/api/admin/trade-audits",Map.of(),admin,202).path("id").asText();
        try {
            assertThat(paused.await(10,TimeUnit.SECONDS)).isTrue(); post("/api/admin/trade-audits",Map.of(),admin,409); mutation.run();resume.countDown();
            var r=await(id); assertThat(r.verdict()).isEqualTo("INCONCLUSIVE");assertThat(r.reason()).isEqualTo("CUT_CHANGED");
            assertThat(store.items(id,0,null,null,null,null).items()).allMatch(i->i.verdict().equals("INCONCLUSIVE"));
        } finally {resume.countDown();reset(validator);}
    }
    private AuditModels.Run run() throws Exception { return await(post("/api/admin/trade-audits",Map.of(),admin,202).path("id").asText()); }
    private AuditModels.Run await(String id) throws Exception {
        for(int i=0;i<350;i++) { var r=audit.detail(id); if(!r.lifecycle().equals("RUNNING")) return r;Thread.sleep(100); }
        throw new AssertionError("Audit worker did not finish");
    }
    private void rpc(String method) {
        var rpc=new HttpService("http://127.0.0.1:26545"); var client=org.web3j.protocol.Web3j.build(rpc);
        try {assertThat(new Request<>(method,List.of(),rpc,VoidResponse.class).send().hasError()).isFalse();}
        catch(Exception e) {throw new AssertionError("Fixture RPC failed");} finally {client.shutdown();}
    }
    private void rotateCurrentSettings() throws Exception {
        var cfg=context.getBean(com.pricetrack.exchange.blockchain.config.BlockchainProperties.class);
        String operator=org.web3j.crypto.Credentials.create(cfg.operatorPrivateKey()).getAddress();
        var rpc=new HttpService("http://127.0.0.1:26545");var client=org.web3j.protocol.Web3j.build(rpc);
        try {
            assertThat(new Request<>("anvil_impersonateAccount",List.of(operator),rpc,VoidResponse.class).send().hasError()).isFalse();
            var fee=new org.web3j.abi.datatypes.Function("setFeeBps",List.of(new org.web3j.abi.datatypes.generated.Uint256(20)),List.of());
            var signer=new org.web3j.abi.datatypes.Function("setPriceSigner",List.of(new org.web3j.abi.datatypes.Address("0x"+"e".repeat(40))),List.of());
            for(var setting:List.of(Map.entry(cfg.exchangeVaultAddress(),fee),Map.entry(cfg.priceOracleAddress(),signer))) {
                var result=new Request<>("eth_sendTransaction",List.of(Map.of("from",operator,"to",setting.getKey(),"data",org.web3j.abi.FunctionEncoder.encode(setting.getValue()),"gas","0x7a120")),rpc,org.web3j.protocol.core.methods.response.EthSendTransaction.class).send();
                assertThat(result.hasError()).isFalse();
            }
            assertThat(new Request<>("anvil_stopImpersonatingAccount",List.of(operator),rpc,VoidResponse.class).send().hasError()).isFalse();
        } finally {client.shutdown();}
    }
    private void settle(long id) throws Exception { for(int i=0;i<100;i++) {reconciliation.reconcilePendingTransactions();if(orders.findById(id).orElseThrow().getStatus()==OrderStatus.FILLED) return;Thread.sleep(100);}throw new AssertionError("Settlement did not finish"); }
    private JsonNode post(String path,Map<String,?> body,String token,int expected) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null) headers.setBearerAuth(token);
        var r=http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);assertThat(r.getStatusCode().value()).as(path).isEqualTo(expected);return r.getBody();
    }
}
