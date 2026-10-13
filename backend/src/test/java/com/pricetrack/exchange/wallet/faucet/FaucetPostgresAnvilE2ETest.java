package com.pricetrack.exchange.wallet.faucet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.wallet.*;
import com.pricetrack.exchange.reserve.*;
import com.pricetrack.exchange.tradeaudit.*;
import com.pricetrack.exchange.market.provider.simulated.SimulatedPriceProvider;
import com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.websocket.publisher.UserWebSocketPublisher;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.*;
import java.time.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.core.DefaultBlockParameterName;

/** One newly prepared empty fixture, retained afterwards. DB clock injection exists ONLY in this test. */
@EnabledIfEnvironmentVariable(named="FAUCET_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @DirtiesContext
class FaucetPostgresAnvilE2ETest {
    static Properties config=new Properties();
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry registry) throws Exception {
        var phaseA=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FAUCET_E2E_RUN"));
        if(phaseA!=null){config=phaseA;phaseA.forEach((k,v)->registry.add(k.toString(),()->v));return;}
        Path root=Path.of(System.getenv("FAUCET_E2E_RUN")).toRealPath();
        if(!root.getParent().equals(Path.of("../deployment/runtime").toRealPath())||!root.getFileName().toString().matches("audit-e2e-\\d{14}-[0-9a-f]{6}"))throw new IllegalStateException("New faucet fixture required");
        try(var in=Files.newInputStream(root.resolve("secrets/test.properties"))){config.load(in);}
        if(!config.getProperty("spring.datasource.url").startsWith("jdbc:postgresql://127.0.0.1:26542/exchange_trade_audit_e2e?")||!config.getProperty("app.blockchain.rpc-url").equals("http://127.0.0.1:26545")||!config.getProperty("app.price.provider").equals("simulated")||!config.getProperty("app.ai.enabled").equals("false"))throw new IllegalStateException("Unsafe fixture");
        config.forEach((k,v)->registry.add(k.toString(),()->v));
    }
    @Autowired TestRestTemplate http;@Autowired JdbcTemplate jdbc;@Autowired FaucetService service;
    @SpyBean FaucetStore store;@SpyBean UserWebSocketPublisher events;
    @Autowired DataSource ds;@Autowired PlatformTransactionManager transactions;
    @Autowired ReserveService reserve;@Autowired ReserveChainReader chain;
    @Autowired AuditService audit;@Autowired WalletService wallets;@Autowired UserBalanceRepository balances;
    @Autowired SimulatedPriceProvider prices;@Autowired OrderRepository orders;
    @Autowired BlockchainReconciliationService reconciliation;@Autowired ApplicationContext context;
    String admin;List<Long> users=new ArrayList<>();List<String> tokens=new ArrayList<>();
    @Test void approvedPoliciesRealConcurrencyAndUnchangedChain() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM faucet_grants",Long.class)).isZero();
        assertThat(context.getBeansOfType(com.pricetrack.exchange.ai.provider.OpenAiProvider.class)).isEmpty();
        for(int i=0;i<12;i++){
            String token=post("/api/auth/signup",Map.of("loginId","faucet_fixture_"+i,"password","fixture-password","nickname","Faucet fixture"),null,null,201).path("accessToken").asText();
            users.add(jdbc.queryForObject("SELECT id FROM users WHERE login_id=?",Long.class,"faucet_fixture_"+i));tokens.add(token);
        }
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?",users.getFirst());admin=post("/api/auth/login",Map.of("loginId","faucet_fixture_0","password","fixture-password"),null,null,200).path("accessToken").asText();
        reserve.createBaseline(users.getFirst());
        // PostgreSQL-backed fixture clock allows calendar transitions without changing any host/Anvil clock.
        jdbc.execute("CREATE TABLE fixture_faucet_clock(at TIMESTAMPTZ NOT NULL)");jdbc.update("INSERT INTO fixture_faucet_clock VALUES(?::timestamptz)","2026-10-10T14:59:00Z");
        doAnswer(c->jdbc.queryForObject("SELECT at FROM fixture_faucet_clock",Timestamp.class).toInstant()).when(store).decisionTime();
        var beforeBlock=chain.head();var beforeChain=chain.read(beforeBlock);
        var transport=new HttpService(config.getProperty("app.blockchain.rpc-url"));
        Web3j rpc=Web3j.build(transport);
        try{
            String operator=beforeChain.identity().operator();
            var nonce=rpc.ethGetTransactionCount(operator,DefaultBlockParameterName.LATEST).send().getTransactionCount();
            String key="first-success-key-12345";
            var first=post("/api/wallet/faucet",Map.of(),tokens.getFirst(),key,200);assertThat(first.path("receivedAmount").decimalValue()).isEqualByComparingTo("1000000");
            verify(events,times(1)).publishPortfolio(users.getFirst());
            post("/api/wallet/faucet",Map.of(),tokens.getFirst(),"same-day-key-12345",429);
            var replay=post("/api/wallet/faucet",Map.of(),tokens.getFirst(),key,200);assertThat(replay.path("grantId")).isEqualTo(first.path("grantId"));assertThat(replay.path("replayed").asBoolean()).isTrue();verify(events,times(1)).publishPortfolio(users.getFirst());
            assertThat(chain.head()).isEqualTo(beforeBlock);assertThat(chain.read(beforeBlock)).isEqualTo(beforeChain);assertThat(rpc.ethGetTransactionCount(operator,DefaultBlockParameterName.LATEST).send().getTransactionCount()).isEqualTo(nonce);
        }finally{rpc.shutdown();}
        get("/api/admin/faucet",tokens.get(1),null,403);get("/api/admin/faucet",null,null,401);get("/api/admin/faucet",admin,null,200);
        get("/api/wallet/faucet/claims",tokens.get(1),"first-success-key-12345",404);
        post("/api/wallet/faucet",Map.of(),tokens.get(1),null,400);
        post("/api/wallet/faucet",Map.of("userId",users.getFirst()),tokens.getFirst(),"first-success-key-12345",409);
        // Wait on the real account row while the DB fixture clock crosses KST midnight.
        try(var connection=ds.getConnection()) {
            connection.setAutoCommit(false);try(var p=connection.prepareStatement("SELECT user_id FROM faucet_account_usage WHERE user_id=? FOR UPDATE")){p.setLong(1,users.getFirst());p.executeQuery().close();}
            var executor=Executors.newSingleThreadExecutor();try{
                var waiting=executor.submit(()->wallets.faucet(users.getFirst(),"midnight-wait-key-12345"));
                waitForLock();clock("2026-10-10T15:01:00Z");connection.commit();assertThat(waiting.get(10,TimeUnit.SECONDS).grantedAt()).isEqualTo(Instant.parse("2026-10-10T15:01:00Z"));
            }finally{executor.shutdownNow();}
        }
        assertThat(service.status(users.getFirst()).remainingCount()).isEqualTo(3);
        var same=parallel(()->wallets.faucet(users.get(1),"concurrent-same-key-123"),()->wallets.faucet(users.get(1),"concurrent-same-key-123"));assertThat(same).noneMatch(x->x instanceof Throwable);assertThat(((FaucetStore.Result)same.get(0)).grantId()).isEqualTo(((FaucetStore.Result)same.get(1)).grantId());assertThat(same.stream().map(x->(FaucetStore.Result)x).filter(FaucetStore.Result::replayed).count()).isEqualTo(1);assertThat(journal(users.get(1))).isEqualTo(1);
        var different=parallel(()->wallets.faucet(users.get(2),"concurrent-other-key-123"),()->wallets.faucet(users.get(2),"concurrent-other-key-456"));assertThat(different.stream().filter(x->x instanceof FaucetFailure).count()).isEqualTo(1);assertThat(journal(users.get(2))).isEqualTo(1);
        var usage=service.admin();doThrow(new IllegalStateException("fixture rollback")).when(events).publishPortfolio(users.get(3));
        assertThatThrownBy(()->wallets.faucet(users.get(3),"rollback-retry-key-12345")).isInstanceOf(IllegalStateException.class);
        assertThat(journal(users.get(3))).isZero();assertThat(service.admin().spent()).isEqualByComparingTo(usage.spent());assertThat(balances.findByUserIdAndSymbol(users.get(3),"mKRW").orElseThrow().getAmount()).isZero();
        doCallRealMethod().when(events).publishPortfolio(users.get(3));wallets.faucet(users.get(3),"rollback-retry-key-12345");
        // Same exact policy, new immutable version; no counter or budget reset.
        FaucetStore v2=new FaucetStore(ds,"kst-v2");v2.initialize();jdbc.update("UPDATE faucet_budget_usage SET active_version='kst-v2'");
        var v2Service=new FaucetService(v2,balances,events);var tx=new TransactionTemplate(transactions);
        assertThat(tx.execute(s->v2Service.credit(users.getFirst(),"first-success-key-12345",FaucetService.REQUEST)).replayed()).isTrue();
        assertThat(v2.count(users.getFirst())).isEqualTo(2);assertThat(v2.spent()).isEqualByComparingTo(usage.spent().add(FaucetPolicy.AMOUNT));
        assertThat(tx.execute(s->v2Service.credit(users.get(4),"v2-new-credit-key-12345",FaucetService.REQUEST)).policyVersion()).isEqualTo("kst-v2");assertThat(v2.count(users.getFirst())).isEqualTo(2);
        jdbc.update("UPDATE faucet_budget_usage SET active_version='kst-v1'");
        // Accounting corruption is observed, never auto-repaired. Restore ONLY the deliberate fixture mutation.
        jdbc.update("UPDATE faucet_budget_usage SET grant_count=grant_count+1");assertThat(service.admin().journalConsistent()).isFalse();assertThatThrownBy(()->wallets.faucet(users.get(4),"blocked-accounting-key-123")).hasMessage("FAUCET_ACCOUNTING_INCONSISTENT");jdbc.update("UPDATE faucet_budget_usage SET grant_count=grant_count-1");
        jdbc.update("UPDATE faucet_budget_usage SET active_version='unknown'");assertThatThrownBy(()->wallets.faucet(users.get(4),"blocked-policy-key-12345")).hasMessage("FAUCET_POLICY_UNAVAILABLE");assertThat(wallets.faucet(users.getFirst(),"first-success-key-12345").replayed()).isTrue();jdbc.update("UPDATE faucet_budget_usage SET active_version='kst-v1'");
        // Real BUY locking and SELL settlement share the mKRW row; faucet never changes lockedAmount.
        prices.tick();var q=post("/api/quotes/buy",Map.of("symbol","mSEC","krwAmount","100000"),admin,null,200);
        clock("2026-10-11T15:01:00Z");
        var race=parallel(()->wallets.faucet(users.getFirst(),"concurrent-buy-key-12345"),()->post("/api/orders/buy",Map.of("symbol","mSEC","krwAmount","100000","quoteId",q.path("quoteId").asText()),admin,null,202));
        assertThat(race).noneMatch(x->x instanceof Throwable);long buy=((JsonNode)race.get(1)).path("orderId").asLong();
        assertThat(balances.findByUserIdAndSymbol(users.getFirst(),"mKRW").orElseThrow().getLockedAmount()).isEqualByComparingTo("100000");
        String txState=jdbc.queryForObject("SELECT status FROM blockchain_transactions WHERE order_id=?",String.class,buy);
        jdbc.update("UPDATE blockchain_transactions SET status='REVIEW_REQUIRED' WHERE order_id=?",buy);clock("2026-10-12T15:01:00Z");
        wallets.faucet(users.getFirst(),"review-pending-key-12345");
        assertThat(jdbc.queryForObject("SELECT status FROM blockchain_transactions WHERE order_id=?",String.class,buy)).isEqualTo("REVIEW_REQUIRED");
        assertThat(orders.findById(buy).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_ONCHAIN);
        assertThat(balances.findByUserIdAndSymbol(users.getFirst(),"mKRW").orElseThrow().getLockedAmount()).isEqualByComparingTo("100000");
        jdbc.update("UPDATE blockchain_transactions SET status=? WHERE order_id=?",txState,buy);settle(buy);
        var qty=balances.findByUserIdAndSymbol(users.getFirst(),"mSEC").orElseThrow().getAmount();prices.tick();var sq=post("/api/quotes/sell",Map.of("symbol","mSEC","tokenAmount",qty.toPlainString()),admin,null,200);
        long sell=post("/api/orders/sell",Map.of("symbol","mSEC","tokenAmount",qty.toPlainString(),"quoteId",sq.path("quoteId").asText()),admin,null,202).path("orderId").asLong();
        clock("2026-10-13T15:01:00Z");assertThat(parallel(()->wallets.faucet(users.getFirst(),"concurrent-sell-key-12345"),()->{settle(sell);return "settled";})).noneMatch(x->x instanceof Throwable);
        assertThat(balances.findByUserIdAndSymbol(users.getFirst(),"mKRW").orElseThrow().getLockedAmount()).isZero();
        assertThat(reserve.run(users.getFirst()).status()).isEqualTo("MATCH");var run=audit.start(users.getFirst());
        var end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(audit.detail(run.id()).lifecycle().equals("RUNNING")&&System.nanoTime()<end)Thread.sleep(30);
        assertThat(audit.detail(run.id()).verdict()).isEqualTo("MATCH");
        // Fill by actual successful credits across distinct DB calendar dates, never fabricate grants.
        int day=14;while(journal(users.getFirst())<5){clock("2026-10-"+day+++"T16:00:00Z");wallets.faucet(users.getFirst(),UUID.randomUUID().toString());}
        assertThatThrownBy(()->wallets.faucet(users.getFirst(),"sixth-payment-key-12345")).hasMessage("FAUCET_LIFETIME_LIMIT");
        for(int i=1;i<10;i++)while(journal(users.get(i))<(i==9?4:5)){
            clock(Instant.parse("2026-10-20T16:00:00Z").plus(day++,java.time.temporal.ChronoUnit.DAYS).toString());wallets.faucet(users.get(i),UUID.randomUUID().toString());
        }
        assertThat(service.admin().grantCount()).isEqualTo(49);assertThat(service.admin().spent()).isEqualByComparingTo("49000000");
        clock(Instant.parse("2027-05-01T00:00:00Z").toString());
        var finalRace=parallel(()->wallets.faucet(users.get(9),"last-budget-key-1234567"),()->wallets.faucet(users.get(10),"last-budget-key-7654321"));assertThat(finalRace.stream().filter(x->x instanceof FaucetFailure).count()).isEqualTo(1);
        assertThat(service.admin().spent()).isEqualByComparingTo("50000000");assertThat(service.admin().journalConsistent()).isTrue();assertThatThrownBy(()->wallets.faucet(users.get(11),"exhausted-budget-key-123")).hasMessage("FAUCET_BUDGET_EXHAUSTED");
        assertThat(wallets.faucet(users.getFirst(),"first-success-key-12345").replayed()).isTrue();
        assertThat(reserve.run(users.getFirst()).status()).isEqualTo("MATCH");
        assertThat(service.history(users.getFirst(),null).items()).hasSize(5);
        for(String table:List.of("faucet_claims","faucet_grants","faucet_policy_versions"))assertThatThrownBy(()->jdbc.update("DELETE FROM "+table)).hasMessageContaining("append-only");
    }
    long journal(long uid){return jdbc.queryForObject("SELECT count(*) FROM faucet_grants WHERE user_id=?",Long.class,uid);}
    void clock(String at){jdbc.update("UPDATE fixture_faucet_clock SET at=?::timestamptz",at);}
    void waitForLock() throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND datname=current_database()",Integer.class)>0)return;Thread.sleep(10);}throw new AssertionError("No actual PostgreSQL lock wait observed");}
    List<Object> parallel(Callable<?> a,Callable<?> b) throws Exception {var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{var x=pool.submit(()->{gate.await();try{return a.call();}catch(Exception e){return e;}});var y=pool.submit(()->{gate.await();try{return b.call();}catch(Exception e){return e;}});gate.countDown();return List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
    void settle(long id) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){reconciliation.reconcilePendingTransactions();if(orders.findById(id).orElseThrow().getStatus()==OrderStatus.FILLED)return;Thread.sleep(50);}throw new AssertionError("Fixture settlement not FILLED");}
    JsonNode post(String path,Object body,String token,String key,int status){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);if(key!=null)h.set("Idempotency-Key",key);var r=http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,h),JsonNode.class);String code=r.getBody()==null?"NO_BODY":r.getBody().path("code").asText();if(!code.matches("[A-Z0-9_]*"))code="UNKNOWN";assertThat(r.getStatusCode().value()).as(path+" publicCode="+code).isEqualTo(status);return r.getBody();}
    JsonNode get(String path,String token,String key,int status){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);if(key!=null)h.set("Idempotency-Key",key);var r=http.exchange(path,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class);assertThat(r.getStatusCode().value()).as(path).isEqualTo(status);return r.getBody();}
}
