package com.pricetrack.exchange.ai.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.ai.tool.ToolFixtures;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;

@SpringBootTest(properties={"app.ai.agent.enabled=true","app.ai.enabled=true","app.ai.tools.enabled=true",
        "app.ai.jdbc-url=jdbc:postgresql://127.0.0.1:1/exchange_ai_unavailable","app.ai.db-password=test",
        "app.blockchain.enabled=false","app.blockchain.price-report.enabled=false","app.admin.password=",
        "spring.datasource.url=jdbc:h2:mem:agent_tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class AgentIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users;@Autowired OrderRepository orders;@Autowired PriceQuoteRepository quotes;
    @Autowired BlockchainTransactionRepository txs;@Autowired JdbcTemplate jdbc;@Autowired WalletService wallets;@Autowired OrderService trading;
    @MockBean AgentModelProvider model;@MockBean AuthorizedKnowledgeRetrieval retrieval;@MockBean ReceiptReader receipts;
    User alice,bob,admin;com.pricetrack.exchange.order.Order order;
    final KnowledgeHit policy=new KnowledgeHit("policy-1","order-lifecycle.md","상태","정책","1","USER","PENDING_ONCHAIN은 정산 대기다.",.8);
    @BeforeEach void fixture(){
        jdbc.update("delete from trades");quotes.deleteAll();txs.deleteAll();orders.deleteAll();jdbc.update("delete from user_balances");users.deleteAll();
        alice=user("agent-alice",UserRole.USER);bob=user("agent-bob",UserRole.USER);admin=user("agent-admin",UserRole.ADMIN);
        order=orders.saveAndFlush(ToolFixtures.order(alice.getId()));
        quotes.saveAndFlush(ToolFixtures.quote(alice.getId(),order.getId()));txs.saveAndFlush(ToolFixtures.tx(order.getId()));
        reset(model,retrieval,receipts);
        when(receipts.read(any())).thenReturn(json.createObjectNode().put("receiptStatus","NOT_FOUND"));
        when(retrieval.search(any(),any(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("index",List.of(policy)));
        when(model.plan(any(),any(),any())).thenReturn(new Plan(Route.MIXED,Subject.ORDER,Usage.none()));
        when(model.synthesize(any(),any(),any(),any())).thenAnswer(c -> {
            JsonNode evidence=c.getArgument(2);
            String pointer=evidence.path("tools").get(0).path("tool").asText().equals("getQuote")?"/storedStatus":"/status";
            String value=evidence.path("tools").get(0).path("data").at(pointer).asText();
            return new Generated("ANSWERED","대기는 체결 확정과 다릅니다.",List.of("policy-1","tool-1"),
                    List.of(new FactReference("tool-1",pointer,value)),List.of(),List.of(),Usage.none());
        });
    }
    User user(String id,UserRole role){User u=new User();u.setLoginId(id);u.setNickname(id);u.setRole(role);u.setPasswordHash("SECRET_HASH");return users.saveAndFlush(u);}
    JsonNode call(User user,String body,int status)throws Exception{
        return json.readTree(mvc.perform(post("/api/ai/agent/answers").header("Authorization","Bearer "+jwt.createToken(user.getId(),user.getLoginId()))
                .contentType("application/json").content(body)).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    String orderBody(){return "{\"question\":\"왜 대기 중인가요?\",\"target\":{\"orderId\":"+order.getId()+"}}";}
    @Test void mixedRealJpaAndDispatcherRemainReadOnlyAndModelSafe()throws Exception{
        var before=snapshot();var result=call(alice,orderBody(),200);
        assertThat(result.path("status").asText()).isEqualTo("PARTIAL");assertThat(result.path("metrics").path("toolCalls").asInt()).isEqualTo(4);
        assertThat(result.path("uncertainties").toString()).contains("RECEIPT_NOT_FOUND");
        assertThat(result.path("toolEvidence").get(0).path("data").path("status").asText()).isEqualTo("PENDING_ONCHAIN");
        assertThat(result.toString()).doesNotContain("SECRET_HASH","SENSITIVE","rawTransaction","signature","executor");
        assertThat(snapshot()).isEqualTo(before);
        verify(model).synthesize(any(),any(),argThat(e -> !e.toString().contains("orderId") && !e.toString().contains("txHash") && !e.toString().contains("quoteId")),any());
    }
    @Test void foreignAndMissingOrderAndQuoteAreIndistinguishableBeforeModel()throws Exception{
        var denied=call(bob,orderBody(),404);var missing=call(bob,"{\"question\":\"상태\",\"target\":{\"orderId\":9223372036854775807}}",404);
        assertThat(denied.path("error")).isEqualTo(missing.path("error"));
        call(bob,"{\"question\":\"견적\",\"target\":{\"quoteId\":\""+ToolFixtures.QUOTE+"\"}}",404);
        verifyNoInteractions(model,retrieval,receipts);
    }
    @Test void adminCanReadOtherOrderAndListButPortfolioIsOwn()throws Exception{
        call(admin,orderBody(),200);
        when(model.plan(any(),any(),any())).thenReturn(new Plan(Route.STATE,Subject.ABNORMAL,Usage.none()));
        assertThat(call(admin,"{\"question\":\"이상 주문 목록\"}",200).path("toolEvidence").get(0).path("data").path("items").size()).isEqualTo(1);
        when(model.plan(any(),any(),any())).thenReturn(new Plan(Route.STATE,Subject.PORTFOLIO,Usage.none()));
        assertThat(call(admin,"{\"question\":\"내 자산\"}",200).path("toolEvidence").get(0).path("data").path("balances").get(0).path("total").asText()).isEqualTo("0");
        call(admin,"{\"question\":\"자산\",\"userId\":"+alice.getId()+"}",400);
    }
    @Test void userAdminOnlyRequestAndExistingRagApisStayDenied()throws Exception{
        when(model.plan(any(),any(),any())).thenReturn(new Plan(Route.STATE,Subject.ABNORMAL,Usage.none()));
        call(alice,"{\"question\":\"이상 주문 목록\"}",403);
        for(String path:List.of("index","search","answers","agent/other","agent/answers/nested"))
            mvc.perform(post("/api/ai/"+path).header("Authorization","Bearer "+jwt.createToken(alice.getId(),alice.getLoginId())).content("{}"))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(retrieval,receipts);
    }
    @Test void jwtAnonymousExpiredAndTamperedAreDenied()throws Exception{
        mvc.perform(post("/api/ai/agent/answers").content("{}")).andExpect(status().isUnauthorized());
        var expired=new JwtTokenProvider("test-secret-test-secret-test-secret-32bytes",-1000).createToken(alice.getId(),"a");
        for(String token:List.of(expired,jwt.createToken(alice.getId(),"a")+"broken"))
            mvc.perform(post("/api/ai/agent/answers").header("Authorization","Bearer "+token).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(model,retrieval,receipts);
    }
    @Test void quoteConsumedAndExpiredRemainSeparate()throws Exception{
        var quote=quotes.findById(ToolFixtures.QUOTE).orElseThrow();quote.setStatus(PriceQuoteStatus.CONSUMED);quotes.saveAndFlush(quote);
        when(model.plan(any(),any(),any())).thenReturn(new Plan(Route.MIXED,Subject.QUOTE,Usage.none()));
        var result=call(alice,"{\"question\":\"이 견적이 왜 만료됐나요?\",\"target\":{\"quoteId\":\""+ToolFixtures.QUOTE+"\"}}",200);
        var data=result.path("toolEvidence").get(0).path("data");
        assertThat(data.path("storedStatus").asText()).isEqualTo("CONSUMED");assertThat(data.path("expiredByTime").asBoolean()).isTrue();
        assertThat(quotes.findById(ToolFixtures.QUOTE).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.CONSUMED);
    }
    @Test void rpcRagAndModelFailureDoNotMutateOrBlockMockTrading()throws Exception{
        var before=snapshot();when(receipts.read(any())).thenThrow(new RuntimeException("SECRET_RPC_MESSAGE"));
        assertThat(call(alice,orderBody(),200).path("status").asText()).isEqualTo("PARTIAL");
        when(retrieval.search(any(),any(),any())).thenThrow(new RuntimeException("SECRET_AI_PASSWORD"));
        assertThat(call(alice,orderBody(),200).path("status").asText()).isEqualTo("PARTIAL");
        when(model.plan(any(),any(),any())).thenThrow(new RuntimeException("SECRET_PROVIDER"));
        var result=call(alice,orderBody(),200);assertThat(result.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(result.toString()).doesNotContain("SECRET_RPC_MESSAGE","SECRET_AI_PASSWORD","SECRET_PROVIDER");assertThat(snapshot()).isEqualTo(before);
        wallets.initializeBalances(bob.getId());wallets.faucet(bob.getId());assertThat(trading.buy(bob.getId(),"mSEC",new BigDecimal("1000"),null).getStatus()).isEqualTo(OrderStatus.FILLED);
    }
    @Test void allFailedDependenciesWithoutFactsReturnSafe503()throws Exception{
        when(model.plan(any(),any(),any())).thenThrow(new RuntimeException("SECRET_PROVIDER"));
        var result=call(alice,"{\"question\":\"정책 설명\"}",503);assertThat(result.path("toolEvidence").isEmpty()).isTrue();
        assertThat(result.toString()).doesNotContain("SECRET_PROVIDER");
    }
    Map<String,List<Map<String,Object>>> snapshot(){Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for(String table:List.of("orders","price_quotes","blockchain_transactions","trades","user_balances"))
            result.put(table,jdbc.queryForList("select * from "+table+" order by "+(table.equals("price_quotes")?"quote_id":"id")));return result;}
}
