package com.pricetrack.exchange.conditional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.oracle.*;
import com.pricetrack.exchange.blockchain.support.*;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.model.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/** H2/Mock only: no PostgreSQL concurrency or real Anvil receipt claims. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:conditional-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
    "app.blockchain.enabled=true","app.blockchain.price-report.enabled=true","app.conditional.enabled=true","app.conditional.fixture=true",
    "app.conditional.initial-delay-ms=2147483647","app.price.initial-delay-ms=2147483647"})
@ActiveProfiles("conditional-fixture") @AutoConfigureMockMvc @DirtiesContext
class ConditionalOrderIntegrationTest {
    @Test void kiwoomUnknownUsesExplicitMockReadinessWithoutInventingOpen() {
        var price=snapshot("75000",MarketStatus.UNKNOWN,PriceStatus.LIVE,"KIWOOM",Instant.now());
        doReturn(new TradingPriceContext(price,true,"REGULAR_TICK",7L)).when(market).currentForTrading();
        assertThat(service.checkedMarket().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
        assertThat(create(OrderSide.BUY,"75000").state()).isEqualTo(ConditionalOrder.State.WAITING);
        doReturn(new TradingPriceContext(price,false,"NO_CURRENT_GENERATION_TICK",8L)).when(market).currentForTrading();
        assertThatThrownBy(service::checkedMarket).hasMessage("MARKET_UNAVAILABLE");
    }
    @Test void kiwoomRegistrationAndExecutionUseTheSameLiveBoundary() {
        when(market.current()).thenAnswer(i->snapshot("75000",MarketStatus.OPEN,PriceStatus.LIVE,"KIWOOM",Instant.now()));
        assertThat(service.registrationEnabled()).isTrue();
        assertThat(service.checkedMarket().provider()).isEqualTo("KIWOOM");
        var created=create(OrderSide.BUY,"75000");
        assertThat(created.state()).isEqualTo(ConditionalOrder.State.WAITING);
        for (var quality:List.of(PriceStatus.DEGRADED,PriceStatus.STALE,PriceStatus.INITIALIZING)) {
            when(market.current()).thenAnswer(i->snapshot("75000",MarketStatus.OPEN,quality,"KIWOOM",Instant.now()));
            assertThatThrownBy(service::checkedMarket).hasMessage("MARKET_UNAVAILABLE");
        }
        when(market.current()).thenAnswer(i->snapshot("75000",MarketStatus.UNKNOWN,PriceStatus.LIVE,"KIWOOM",Instant.now()));
        assertThatThrownBy(service::checkedMarket).hasMessage("MARKET_UNAVAILABLE");
    }
    @Autowired ConditionalOrderService service;@Autowired ConditionalOrderWorker worker;
    @Autowired ConditionalOrderRepository intents;@Autowired PriceQuoteRepository quotes;
    @Autowired UserRepository users;@Autowired WalletService wallets;@Autowired UserBalanceRepository balances;
    @Autowired OrderRepository orders;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @MockBean BlockchainService chain;@MockBean PriceReportStartupValidator validator;
    @MockBean BlockchainTransactionSender sender;@MockBean MarketPriceService market;
    @org.springframework.boot.test.mock.mockito.SpyBean org.springframework.messaging.simp.SimpMessagingTemplate broker;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    long uid;
    @Test void conditionalNotificationsAreCommitOnly(){
        clearInvocations(broker);String key=UUID.randomUUID().toString();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{service.create(uid,key,request(OrderSide.BUY,"75000"));status.setRollbackOnly();return null;});
        verify(broker,never()).convertAndSendToUser(eq(Long.toString(uid)),eq("/queue/conditional-orders"),any());
        assertThat(intents.findByUserIdAndRequestKeyHash(uid,ConditionalOrderService.key(key))).isEmpty();
        service.create(uid,key,request(OrderSide.BUY,"75000"));
        verify(broker,times(1)).convertAndSendToUser(eq(Long.toString(uid)),eq("/queue/conditional-orders"),any());
    }
    @BeforeEach void account(){when(market.currentForTrading()).thenAnswer(i->TradingPriceContext.standard(market.current()));when(market.current()).thenAnswer(i->snapshot("75000",MarketStatus.OPEN,PriceStatus.SIMULATED,"SIMULATED",Instant.now()));when(market.currentPrice()).thenReturn(new BigDecimal("75000"));
        User u=new User();u.setLoginId("conditional"+System.nanoTime());u.setNickname("Fixture");u.setPasswordHash("fixture-not-used-for-login");u=users.save(u);uid=u.getId();wallets.initializeBalances(uid);wallets.faucet(uid,UUID.randomUUID().toString());}
    static MarketPriceSnapshot snapshot(String p,MarketStatus m,PriceStatus s,String provider,Instant at){return new MarketPriceSnapshot("mSEC",new BigDecimal(p),new BigDecimal("75000"),BigDecimal.ZERO,BigDecimal.ZERO,m,s,provider,at);}
    ConditionalOrderService.Request request(OrderSide side,String target){return new ConditionalOrderService.Request("mSEC",side,new BigDecimal("100000"),new BigDecimal(target),Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));}
    ConditionalOrderService.View create(OrderSide side,String target){return service.create(uid,UUID.randomUUID().toString(),request(side,target));}
    PriceQuote quote(String id,String price){PriceQuote q=new PriceQuote();q.setQuoteId(id);q.setUserId(uid);q.setSymbol("mSEC");q.setSide(PriceReport.Side.BUY);q.setPriceE8(PriceUnits.toPriceE8(new BigDecimal(price)));q.setInputAmount(TokenUnits.toWei(new BigDecimal("100000")));q.setMinimumOutput(TokenUnits.toWei(BigDecimal.ONE));q.setFee(TokenUnits.toWei(new BigDecimal("100")));q.setExecutor("0x"+"12".repeat(20));q.setSignature("0x"+"ab".repeat(65));q.setObservedAt(Instant.now());q.setValidUntil(Instant.now().plusSeconds(20));q.setCreatedAt(Instant.now());return quotes.save(q);}
    static UsernamePasswordAuthenticationToken actor(long uid,UserRole role){return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(uid,"fixture",role),"unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    @Test void registrationStoresIntentOnlyAndReplaysWithoutNewAssets(){String key=UUID.randomUUID().toString();var r=request(OrderSide.BUY,"75000");var a=service.create(uid,key,r);assertThat(service.create(uid,key,r).id()).isEqualTo(a.id());assertThat(a.state()).isEqualTo(ConditionalOrder.State.WAITING);assertThat(jdbc.queryForObject("SELECT count(*) FROM price_quotes WHERE user_id=?",Long.class,uid)).isZero();assertThat(orders.findAllByUserIdOrderByCreatedAtDesc(uid)).isEmpty();assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isZero();assertThatThrownBy(()->service.create(uid,key,request(OrderSide.BUY,"76000"))).hasMessage("CONDITIONAL_KEY_CONFLICT");}
    @Test void integerComparisonsIncludeBothBoundaries(){BigInteger t=PriceUnits.toPriceE8(new BigDecimal("75000.00000001"));assertThat(ConditionalOrderService.matches(OrderSide.BUY,t,t)).isTrue();assertThat(ConditionalOrderService.matches(OrderSide.SELL,t,t)).isTrue();assertThat(ConditionalOrderService.matches(OrderSide.BUY,t.add(BigInteger.ONE),t)).isFalse();assertThat(ConditionalOrderService.matches(OrderSide.SELL,t.subtract(BigInteger.ONE),t)).isFalse();}
    @Test void unmatchedBuyAndSellDoNotIssueQuote(){var b=create(OrderSide.BUY,"74999");var s=create(OrderSide.SELL,"75001");assertThat(service.claim(b.id(),"owner")).isNull();assertThat(service.claim(s.id(),"owner")).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM price_quotes WHERE user_id=?",Long.class,uid)).isZero();}
    @Test void guardedMarketLabelsFreshnessAndProvider(){for(var status:List.of(PriceStatus.STALE,PriceStatus.INITIALIZING,PriceStatus.DEGRADED)){when(market.current()).thenReturn(snapshot("75000",MarketStatus.OPEN,status,"TOSS",Instant.now()));assertThatThrownBy(service::checkedMarket).hasMessage("MARKET_UNAVAILABLE");}
        when(market.current()).thenReturn(snapshot("75000",MarketStatus.CLOSED,PriceStatus.LIVE,"TOSS",Instant.now()));assertThatThrownBy(service::checkedMarket).hasMessage("MARKET_UNAVAILABLE");when(market.current()).thenReturn(snapshot("75000",MarketStatus.UNKNOWN,PriceStatus.LIVE,"TOSS",Instant.now()));assertThatThrownBy(service::checkedMarket).hasMessage("MARKET_UNAVAILABLE");when(market.current()).thenReturn(snapshot("75000",MarketStatus.OPEN,PriceStatus.LIVE,"TOSS",Instant.now().minusSeconds(6)));assertThatThrownBy(service::checkedMarket).hasMessage("MARKET_UNAVAILABLE");when(market.current()).thenThrow(new IllegalStateException("fixture outage"));assertThatThrownBy(service::checkedMarket).hasMessage("fixture outage");}
    @Test void cancellationBeforePreparationPreventsExecution(){var c=create(OrderSide.BUY,"75000");service.claim(c.id(),"owner");service.cancel(uid,c.id());var q=quote("0x"+UUID.randomUUID().toString().replace("-","")+"aa".repeat(16),"75000");assertThatThrownBy(()->service.prepare(c.id(),"owner",q.getQuoteId())).hasMessage("CONDITIONAL_CLAIM_LOST");assertThat(quotes.findById(q.getQuoteId()).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.ISSUED);assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isZero();}
    @Test void quotePriceChangedRollsBackPreparation(){var c=create(OrderSide.BUY,"75000");service.claim(c.id(),"owner");var q=quote("0x"+"23".repeat(32),"75001");assertThatThrownBy(()->service.prepare(c.id(),"owner",q.getQuoteId())).hasMessage("QUOTE_CONDITION_CHANGED");assertThat(orders.findAllByUserIdOrderByCreatedAtDesc(uid)).isEmpty();assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isZero();}
    @Test void prepareCommitsLinkQuoteAndLockTogetherThenCannotCancel(){var c=create(OrderSide.BUY,"75000");service.claim(c.id(),"owner");var q=quote("0x"+"34".repeat(32),"75000");var prepared=service.prepare(c.id(),"owner",q.getQuoteId());assertThat(service.find(uid,c.id()).executionOrderId()).isEqualTo(prepared.order().getId());assertThat(quotes.findById(q.getQuoteId()).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.CONSUMED);assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isEqualByComparingTo("100000");assertThatThrownBy(()->service.cancel(uid,c.id())).hasMessage("CONDITIONAL_NOT_CANCELLABLE");service.refresh(c.id());assertThat(service.find(uid,c.id()).state()).isEqualTo(ConditionalOrder.State.REVIEW_REQUIRED);assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isEqualByComparingTo("100000");}
    @Test void expiredQuoteDoesNotConsumeOrLock(){var c=create(OrderSide.BUY,"75000");service.claim(c.id(),"owner");var q=quote("0x"+"45".repeat(32),"75000");q.setValidUntil(Instant.now().minusSeconds(1));quotes.save(q);assertThatThrownBy(()->service.prepare(c.id(),"owner",q.getQuoteId())).isInstanceOf(PriceQuoteUnavailableException.class);assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isZero();}
    @Test void finalMarketFailureRollsBackOrderQuoteAndBalance(){
        var c=create(OrderSide.BUY,"75000");service.claim(c.id(),"owner");var q=quote("0x"+"56".repeat(32),"75000");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(market.current()).thenAnswer(i->{if(calls.incrementAndGet()>1)throw new IllegalStateException("late fixture outage");return snapshot("75000",MarketStatus.OPEN,PriceStatus.SIMULATED,"SIMULATED",Instant.now());});
        assertThatThrownBy(()->service.prepare(c.id(),"owner",q.getQuoteId())).hasMessage("late fixture outage");
        assertThat(orders.findAllByUserIdOrderByCreatedAtDesc(uid)).isEmpty();
        assertThat(quotes.findById(q.getQuoteId()).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.ISSUED);
        assertThat(service.find(uid,c.id()).executionOrderId()).isNull();
        assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isZero();
    }
    @Test void latestBuyAndSellConditionIsCheckedBeforeAndAfterAssetLock(){
        for(var side:OrderSide.values())for(boolean finalCheck:List.of(false,true)){
            when(market.current()).thenAnswer(i->snapshot("75000",MarketStatus.OPEN,PriceStatus.SIMULATED,"SIMULATED",Instant.now()));
            var asset=balances.findByUserIdAndSymbol(uid,side==OrderSide.BUY?"mKRW":"mSEC").orElseThrow();
            asset.setAmount(new BigDecimal("1000000"));balances.save(asset);
            var c=create(side,"75000");service.claim(c.id(),"owner");
            var q=quote("0x"+UUID.randomUUID().toString().replace("-","")+"ab".repeat(16),"75000");
            q.setSide(side==OrderSide.BUY?PriceReport.Side.BUY:PriceReport.Side.SELL);quotes.save(q);
            var calls=new java.util.concurrent.atomic.AtomicInteger();String out=side==OrderSide.BUY?"75000.00000001":"74999.99999999";
            when(market.current()).thenAnswer(i->snapshot(finalCheck&&calls.incrementAndGet()==1?"75000":out,MarketStatus.OPEN,PriceStatus.SIMULATED,"SIMULATED",Instant.now()));
            assertThatThrownBy(()->service.prepare(c.id(),"owner",q.getQuoteId())).hasMessage("PRICE_CONDITION_CHANGED");
            assertThat(service.find(uid,c.id()).executionOrderId()).isNull();
            assertThat(orders.findAllByUserIdOrderByCreatedAtDesc(uid)).isEmpty();
            assertThat(quotes.findById(q.getQuoteId()).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.ISSUED);
            assertThat(balances.findById(asset.getId()).orElseThrow().getLockedAmount()).isZero();
            service.defer(c.id(),"owner","PRICE_CONDITION_CHANGED",false);
            assertThat(service.find(uid,c.id()).state()).isEqualTo(ConditionalOrder.State.WAITING);
        }
    }
    @Test void insufficientBalanceDoesNotLeaveAnOrphanExecution(){
        var request=new ConditionalOrderService.Request("mSEC",OrderSide.BUY,new BigDecimal("2000000"),new BigDecimal("75000"),Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        var c=service.create(uid,UUID.randomUUID().toString(),request);service.claim(c.id(),"owner");
        var q=quote("0x"+"67".repeat(32),"75000");q.setInputAmount(TokenUnits.toWei(new BigDecimal("2000000")));quotes.save(q);
        assertThatThrownBy(()->service.prepare(c.id(),"owner",q.getQuoteId())).isInstanceOf(com.pricetrack.exchange.common.exception.InsufficientBalanceException.class);
        assertThat(orders.findAllByUserIdOrderByCreatedAtDesc(uid)).isEmpty();
        assertThat(service.find(uid,c.id()).executionOrderId()).isNull();
        assertThat(balances.findByUserIdAndSymbol(uid,"mKRW").orElseThrow().getLockedAmount()).isZero();
    }
    @Test void expiredIntentAndRestartBeforeOrder(){var c=create(OrderSide.BUY,"75000");service.claim(c.id(),"old-owner");assertThat(service.claim(c.id(),"new-owner").getWorkerOwner()).isEqualTo("new-owner");var row=intents.findById(c.id()).orElseThrow();row.setExpiresAt(Instant.now().minusSeconds(1));intents.save(row);assertThat(service.claim(c.id(),"new-owner")).isNull();assertThat(service.find(uid,c.id()).state()).isEqualTo(ConditionalOrder.State.EXPIRED);}
    @Test void userAdminAuthorizationAndOwner() throws Exception {var c=create(OrderSide.BUY,"75000");mvc.perform(get("/api/conditional-orders")).andExpect(status().isUnauthorized());mvc.perform(get("/api/conditional-orders/"+c.id()).with(authentication(actor(uid+999,UserRole.USER)))).andExpect(status().isNotFound());mvc.perform(get("/api/admin/conditional-orders").with(authentication(actor(uid,UserRole.USER)))).andExpect(status().isForbidden());mvc.perform(get("/api/admin/conditional-orders").with(authentication(actor(uid,UserRole.ADMIN)))).andExpect(status().isOk());mvc.perform(post("/api/admin/conditional-orders").with(authentication(actor(uid,UserRole.ADMIN)))).andExpect(status().isMethodNotAllowed());mvc.perform(post("/api/conditional-orders").contentType("application/json").content(json.writeValueAsString(request(OrderSide.BUY,"75000"))).with(authentication(actor(uid,UserRole.USER)))).andExpect(status().isBadRequest());}
}
